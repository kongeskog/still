package com.kongeskog.still.bridge

import android.util.Log
import com.kongeskog.still.bridge.db.GroupMemberRow
import com.kongeskog.still.bridge.db.RecipientRow
import com.kongeskog.still.bridge.db.StillDatabase
import com.kongeskog.still.bridge.db.ThreadRow
import org.signal.core.models.AccountEntropyPool
import org.signal.core.models.ServiceId.ACI
import org.signal.network.service.StorageServiceService
import org.signal.libsignal.zkgroup.auth.AuthCredentialWithPniResponse
import org.signal.storageservice.storage.protos.groups.AccessControl
import org.signal.storageservice.storage.protos.groups.GroupChange
import org.signal.storageservice.storage.protos.groups.Member
import org.whispersystems.signalservice.api.groupsv2.GroupsV2Operations
import org.whispersystems.signalservice.api.groupsv2.GroupCandidate
import org.signal.libsignal.zkgroup.groups.GroupMasterKey
import org.signal.libsignal.zkgroup.groups.GroupSecretParams
import org.signal.libsignal.zkgroup.profiles.ProfileKey
import org.signal.network.exceptions.NonSuccessfulResponseCodeException
import org.whispersystems.signalservice.api.crypto.AttachmentCipherInputStream
import org.whispersystems.signalservice.api.crypto.ProfileCipher
import org.whispersystems.signalservice.api.groupsv2.GroupsV2AuthorizationString
import org.whispersystems.signalservice.api.messages.multidevice.DeviceContactsReader
import org.whispersystems.signalservice.api.messages.multidevice.RequestMessage
import org.whispersystems.signalservice.api.messages.multidevice.SignalServiceSyncMessage
import org.whispersystems.signalservice.api.profiles.SignalServiceProfile
import org.whispersystems.signalservice.api.push.SignalServiceAddress
import org.whispersystems.signalservice.api.util.AttachmentPointerUtil
import org.whispersystems.signalservice.internal.push.AttachmentPointer
import org.whispersystems.signalservice.internal.push.SyncMessage
import java.io.File
import java.util.Base64
import java.util.Locale
import java.util.Optional
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Where names come from. Three sources, all read-only towards other people:
 *  - the contact list of the primary device, asked for with a sync request to our own account;
 *  - each person's profile, decrypted with the profile key they sent us;
 *  - group state from the groups server: title, members, and the members' profile keys.
 *
 * Ported from signal-cli 0.14.10 `SyncHelper`, `ProfileHelper` and `GroupV2Helper`.
 */
internal class Directory(
    private val deps: SignalDependencies,
    private val db: StillDatabase,
    private val store: AccountStore,
    private val cacheDir: File,
) {
    private val dao = db.chats()
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "still-names").apply { isDaemon = true } }
    private val queued = AtomicBoolean(false)
    private var lastStorageCheck = 0L
    private var credentials: Map<Long, AuthCredentialWithPniResponse>? = null

    /** Fetches whatever is missing. Cheap when nothing is; safe to call after every message. */
    fun refresh() {
        if (!queued.compareAndSet(false, true)) return
        executor.execute {
            queued.set(false)
            attempt("contact request") { requestContactsIfDue() }
            attempt("storage read") { readStorageIfDue() }
            for (group in dao.groupsNeverFetched()) attempt("group") { fetchGroup(group) }
            val before = System.currentTimeMillis() - PROFILE_MAX_AGE
            for (recipient in dao.recipientsNeedingProfile(before, 200)) attempt("profile") { fetchProfile(recipient) }
        }
    }

    fun importContacts(pointer: AttachmentPointer) = executor.execute { attempt("contact import") { readContacts(pointer) } }

    private fun attempt(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            // Types only: messages can carry identifiers.
            Log.w(TAG, "$what failed: ${e.javaClass.simpleName}")
        }
    }

    /** Goes to this account's other devices only. The primary answers with its contact list. */
    private fun requestContactsIfDue() {
        val last = store.string(CONTACTS_REQUESTED_AT)?.toLongOrNull() ?: 0
        if (System.currentTimeMillis() - last < CONTACTS_MAX_AGE) return
        val result = deps.messageSender.sendSyncMessage(
            SignalServiceSyncMessage.forRequest(RequestMessage.forType(SyncMessage.Request.Type.CONTACTS)),
        )
        Log.i(TAG, "Asked the primary device for contacts: success=${result.isSuccess}")
        if (result.isSuccess) store.putAll(mapOf(CONTACTS_REQUESTED_AT to System.currentTimeMillis()))
    }

    private fun readContacts(blob: AttachmentPointer) {
        val pointer = AttachmentPointerUtil.createSignalAttachmentPointer(blob)
        val digest = pointer.digest.orElse(null) ?: return
        val file = File.createTempFile("contacts", null, cacheDir)
        var count = 0
        try {
            deps.messageReceiver.retrieveAttachment(
                pointer, file, MAX_CONTACTS_SIZE, AttachmentCipherInputStream.IntegrityCheck.forEncryptedDigest(digest),
            ).use { input ->
                db.runInTransaction {
                    for (contact in DeviceContactsReader(input).contacts()) {
                        // The avatar follows its record in the same stream and has to be read past.
                        contact.avatar?.inputStream?.let { stream -> val sink = ByteArray(8192); while (stream.read(sink) >= 0) Unit }
                        val aci = contact.aci?.toString() ?: continue
                        val id = upsert(aci, contact.e164)
                        contact.name?.trim()?.ifEmpty { null }?.let { dao.setRecipientName(id, it) }
                        count++
                    }
                }
            }
        } finally {
            file.delete()
        }
        Log.i(TAG, "Imported $count contacts")
    }

    /**
     * Signal's storage service holds the account's full list of contacts and groups,
     * encrypted with a key derived from the account entropy pool. Read only: Still never
     * writes there. From signal-cli `StorageHelper.readDataFromStorage`.
     */
    private fun readStorageIfDue() {
        if (System.currentTimeMillis() - lastStorageCheck < STORAGE_CHECK_EVERY) return
        val pool = store.string(AccountStore.ACCOUNT_ENTROPY_POOL)
        if (pool == null) {
            Log.w(TAG, "No account entropy pool; cannot read the storage service")
            lastStorageCheck = System.currentTimeMillis()
            return
        }
        val key = AccountEntropyPool(pool).deriveMasterKey().deriveStorageServiceKey()
        val manifest = when (val result = deps.storageService.getStorageManifest(key)) {
            is StorageServiceService.ManifestResult.Success -> result.manifest
            else -> {
                Log.w(TAG, "Storage manifest: ${result.javaClass.simpleName}")
                return
            }
        }
        lastStorageCheck = System.currentTimeMillis()
        if (store.string(STORAGE_VERSION)?.toLongOrNull() == manifest.version) return
        val ids = manifest.storageIds.filter { it.type == TYPE_CONTACT || it.type == TYPE_GROUP_V2 || it.type == TYPE_ACCOUNT }
        val records = when (val result = deps.storageService.readStorageRecords(key, manifest.recordIkm, ids)) {
            is StorageServiceService.StorageRecordResult.Success -> result.records
            else -> {
                Log.w(TAG, "Storage records: ${result.javaClass.simpleName}")
                return
            }
        }
        var contacts = 0
        var groups = 0
        db.runInTransaction {
            for (record in records) {
                record.proto.contact?.let { contact ->
                    val aci = ACI.parseOrNull(contact.aci, contact.aciBinary)?.toString() ?: return@let
                    val id = upsert(aci, contact.e164.ifEmpty { null })
                    if (contact.profileKey.size == 32) dao.setRecipientProfileKey(id, contact.profileKey.toByteArray())
                    // What the user calls them wins: a nickname, else the address-book name.
                    (join(contact.nickname?.given, contact.nickname?.family) ?: join(contact.systemGivenName, contact.systemFamilyName))
                        ?.let { dao.setRecipientName(id, it) }
                    join(contact.givenName, contact.familyName)?.let { dao.setProfileNameIfMissing(id, it) }
                    dao.threadByRecipient(id)?.let { dao.setBlocked(it.id, contact.blocked); dao.setArchived(it.id, contact.archived) }
                        ?: if (contact.blocked) { dao.insertThread(ThreadRow(recipientId = id, blocked = true)) } else Unit
                    contacts++
                }
                record.proto.groupV2?.let { group ->
                    if (group.masterKey.size != 32) return@let
                    val bytes = group.masterKey.toByteArray()
                    val groupId = Base64.getEncoder().encodeToString(
                        GroupSecretParams.deriveFromMasterKey(GroupMasterKey(bytes)).publicParams.groupIdentifier.serialize(),
                    )
                    val existing = dao.threadByGroup(groupId)
                    if (existing == null) dao.insertThread(ThreadRow(groupId = groupId, groupMasterKey = bytes, blocked = group.blocked))
                    else { dao.setBlocked(existing.id, group.blocked); dao.setArchived(existing.id, group.archived) }
                    groups++
                }
            }
            // Pins come last, once every conversation they can name exists.
            records.firstNotNullOfOrNull { it.proto.account }?.let { account ->
                dao.clearPins()
                for (pin in account.pinnedConversations) {
                    val thread = pin.groupMasterKey?.takeIf { it.size == 32 }?.let { key ->
                        dao.threadByGroup(Base64.getEncoder().encodeToString(GroupSecretParams.deriveFromMasterKey(GroupMasterKey(key.toByteArray())).publicParams.groupIdentifier.serialize()))?.id
                    } ?: pin.contact?.let { ACI.parseOrNull(it.serviceId, it.serviceIdBinary) }?.let { aci ->
                        val id = upsert(aci.toString(), null)
                        dao.threadByRecipient(id)?.id ?: dao.insertThread(ThreadRow(recipientId = id))
                    }
                    thread?.let { dao.setPinned(it, true) }
                }
            }
        }
        store.putAll(mapOf(STORAGE_VERSION to manifest.version))
        Log.i(TAG, "Read $contacts contacts and $groups groups from the storage service")
    }

    private fun join(given: String?, family: String?): String? =
        listOfNotNull(given?.trim(), family?.trim()).filter(String::isNotEmpty).joinToString(" ").ifEmpty { null }

    private fun fetchProfile(recipient: RecipientRow) {
        val key = ProfileKey(recipient.profileKey ?: return)
        val response = deps.profileService.getProfile(
            SignalServiceAddress(ACI.parseOrThrow(recipient.aci ?: return)), Optional.of(key), null,
            SignalServiceProfile.RequestType.PROFILE, Locale.getDefault(),
        ).blockingGet()
        val encrypted = response.result.orElse(null)?.profile?.name
        val name = encrypted?.let {
            try {
                // Given and family name are joined by a NUL.
                ProfileCipher(key).decryptString(Base64.getDecoder().decode(it))
                    .split('\u0000').map(String::trim).filter(String::isNotEmpty).joinToString(" ").ifEmpty { null }
            } catch (e: Exception) {
                null
            }
        }
        // Stamped even on failure, so a profile that cannot be read is not asked for on every message.
        dao.setProfile(recipient.id, name ?: recipient.profileName, System.currentTimeMillis())
    }

    private fun fetchGroup(thread: ThreadRow) {
        val params = GroupSecretParams.deriveFromMasterKey(GroupMasterKey(thread.groupMasterKey ?: return))
        val group = try {
            deps.groupsV2Api.getGroup(params, authorization(params)).group
        } catch (e: NonSuccessfulResponseCodeException) {
            // 403: we are no longer a member. Stop asking; keep what we have.
            if (e.code == 403 || e.code == 404) dao.setGroup(thread.id, thread.title, 0, thread.expireTimer, false)
            throw e
        }
        db.runInTransaction {
            val members = group.members.mapNotNull { member ->
                val aci = ACI.parseOrNull(member.aciBytes)?.toString() ?: return@mapNotNull null
                val id = upsert(aci, null)
                if (member.profileKey.size == 32) dao.setRecipientProfileKey(id, member.profileKey.toByteArray())
                GroupMemberRow(thread.id, id)
            }
            dao.clearMembers(thread.id)
            dao.insertMembers(members)
            val self = deps.credentials.aci.toString()
            val admin = group.members.any { ACI.parseOrNull(it.aciBytes)?.toString() == self && it.role == Member.Role.ADMINISTRATOR }
            val open = group.accessControl?.attributes == AccessControl.AccessRequired.MEMBER
            dao.setGroup(
                thread.id, group.title.ifBlank { null }, group.revision.coerceAtLeast(0),
                group.disappearingMessagesTimer?.duration ?: 0, canEdit = admin || open,
            )
        }
        Log.i(TAG, "Fetched a group with ${group.members.size} members")
    }

    /**
     * Changes a group's settings on the groups server, then hands the signed change to
     * [announce] so the members can be told. From signal-cli `GroupV2Helper.commitChange`.
     */
    fun changeGroup(
        threadId: Long,
        change: (GroupsV2Operations.GroupOperations) -> GroupChange.Actions.Builder,
        /** Gets the signed change and who was in the group before it. */
        announce: (ByteArray, List<String>) -> Unit,
    ) = executor.execute {
        attempt("group change") {
            // Start from the server's current state: a change against a stale revision is refused.
            dao.thread(threadId)?.let(::fetchGroup)
            val thread = dao.thread(threadId) ?: return@attempt
            val before = dao.memberAcis(threadId)
            val params = GroupSecretParams.deriveFromMasterKey(GroupMasterKey(thread.groupMasterKey ?: return@attempt))
            val actions = change(deps.groupsV2Operations.forGroup(params)).version(thread.groupRevision + 1).build()
            val signed = deps.groupsV2Api.patchGroup(actions, authorization(params), Optional.empty()).group_change ?: return@attempt
            // After leaving, the server no longer shows us the group; that is not a failure.
            try { fetchGroup(thread) } catch (e: Exception) { }
            Log.i(TAG, "Group changed")
            announce(signed.encode(), before)
        }
    }

    /** What the groups server needs to add someone: their id and, if we can get it, proof of their profile key. */
    fun candidate(recipientId: Long): GroupCandidate? {
        val recipient = dao.recipient(recipientId) ?: return null
        val aci = ACI.parseOrNull(recipient.aci ?: return null) ?: return null
        val credential = try {
            recipient.profileKey?.let { key ->
                deps.profileService.getProfile(
                    SignalServiceAddress(aci), Optional.of(ProfileKey(key)), null,
                    SignalServiceProfile.RequestType.PROFILE_AND_CREDENTIAL, Locale.getDefault(),
                ).blockingGet().result.orElse(null)?.expiringProfileKeyCredential?.orElse(null)
            }
        } catch (e: Exception) {
            null
        }
        // Without the proof they are invited rather than added outright.
        return GroupCandidate(aci, Optional.ofNullable(credential))
    }

    /**
     * Creates a group on the groups server with us as its admin and returns its conversation,
     * or null if it could not be made. From signal-cli `GroupV2Helper.createGroup`.
     */
    fun createGroup(name: String, memberIds: List<Long>): Long? = try {
        val self = deps.credentials.aci
        val ownKey = ProfileKey(store.bytes(AccountStore.PROFILE_KEY) ?: throw IllegalStateException("No profile key"))
        val ownCredential = deps.profileService.getProfile(
            SignalServiceAddress(self), Optional.of(ownKey), null, SignalServiceProfile.RequestType.PROFILE_AND_CREDENTIAL, Locale.getDefault(),
        ).blockingGet().result.orElse(null)?.expiringProfileKeyCredential?.orElse(null) ?: throw IllegalStateException("No own credential")
        val params = GroupSecretParams.generate()
        val group = deps.groupsV2Operations.createNewGroup(
            params, name, Optional.empty(), GroupCandidate(self, Optional.of(ownCredential)),
            memberIds.mapNotNull(::candidate).toSet(), Member.Role.DEFAULT, 0,
        )
        deps.groupsV2Api.putNewGroup(group, authorization(params))
        val key = params.masterKey.serialize()
        val groupId = Base64.getEncoder().encodeToString(params.publicParams.groupIdentifier.serialize())
        val thread = dao.insertThread(ThreadRow(groupId = groupId, groupMasterKey = key, title = name))
        dao.thread(thread)?.let(::fetchGroup)
        Log.i(TAG, "Group created")
        thread
    } catch (e: Exception) {
        Log.w(TAG, "Group creation failed: ${e.javaClass.simpleName}")
        null
    }

    fun removeMembers(threadId: Long) = dao.clearMembers(threadId)

    /** Group credentials are issued per day, a week at a time. */
    private fun authorization(params: GroupSecretParams): GroupsV2AuthorizationString {
        val today = TimeUnit.DAYS.toSeconds(TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis()))
        val all = credentials?.takeIf { it.containsKey(today) }
            ?: deps.groupsV2Api.getCredentials(today).authCredentialWithPniResponseHashMap.also { credentials = it }
        return deps.groupsV2Api.getGroupsV2AuthorizationString(
            deps.credentials.aci, deps.credentials.pni, store.bytes(AccountStore.AUTH_CREDENTIAL_SALT), today, params, all[today],
        )
    }

    private fun upsert(aci: String, e164: String?): Long {
        val existing = dao.recipientByAci(aci) ?: return dao.insertRecipient(RecipientRow(aci = aci, e164 = e164))
        if (e164 != null && existing.e164 != e164) dao.setRecipientNumber(existing.id, e164)
        return existing.id
    }

    private companion object {
        const val TAG = "StillNames"
        const val CONTACTS_REQUESTED_AT = "contacts.requested_at"
        const val CONTACTS_MAX_AGE = 7 * 24 * 3600_000L
        const val PROFILE_MAX_AGE = 30 * 24 * 3600_000L
        const val STORAGE_VERSION = "storage.version"
        const val STORAGE_CHECK_EVERY = 6 * 3600_000L
        // ManifestRecord.Identifier.Type
        const val TYPE_CONTACT = 1
        const val TYPE_GROUP_V2 = 3
        const val TYPE_ACCOUNT = 4
        const val MAX_CONTACTS_SIZE = 50L * 1024 * 1024
    }
}
