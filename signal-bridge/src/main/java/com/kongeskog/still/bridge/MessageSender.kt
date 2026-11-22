package com.kongeskog.still.bridge

import android.util.Log
import com.kongeskog.still.bridge.db.AttachmentRow
import com.kongeskog.still.bridge.db.MessageRow
import com.kongeskog.still.bridge.db.SendLogRow
import org.signal.libsignal.zkgroup.groups.GroupSecretParams
import org.whispersystems.signalservice.api.push.exceptions.RateLimitException
import org.whispersystems.signalservice.api.messages.SendMessageResult
import org.whispersystems.signalservice.internal.push.Content
import org.whispersystems.signalservice.api.crypto.AttachmentCipherStreamUtil
import org.whispersystems.signalservice.api.messages.SignalServiceAttachment
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentPointer
import org.whispersystems.signalservice.api.util.AttachmentPointerUtil
import org.whispersystems.signalservice.internal.crypto.PaddingInputStream
import org.whispersystems.signalservice.internal.push.AttachmentPointer
import java.io.File
import java.util.UUID
import com.kongeskog.still.bridge.db.ReactionRow
import com.kongeskog.still.bridge.db.StillDatabase
import com.kongeskog.still.bridge.db.ThreadRow
import com.kongeskog.still.bridge.db.UnreadRow
import org.signal.core.models.ServiceId.ACI
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.metadata.ProtocolException
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.DecryptionErrorMessage
import org.whispersystems.signalservice.internal.push.Envelope
import org.signal.libsignal.metadata.certificate.SenderCertificate
import org.signal.libsignal.zkgroup.groups.GroupMasterKey
import org.signal.libsignal.zkgroup.profiles.ProfileKey
import org.signal.network.crypto.SealedSenderAccess
import org.signal.network.crypto.UnidentifiedAccess
import org.whispersystems.signalservice.api.messages.SignalServiceEditMessage
import org.signal.network.exceptions.NonSuccessfulResponseCodeException
import org.whispersystems.signalservice.api.SignalServiceMessageSender
import org.whispersystems.signalservice.api.crypto.ContentHint
import org.whispersystems.signalservice.api.crypto.UntrustedIdentityException
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage
import org.whispersystems.signalservice.api.messages.SignalServiceGroupV2
import org.whispersystems.signalservice.api.messages.SignalServiceReceiptMessage
import org.whispersystems.signalservice.api.messages.SignalServiceTypingMessage
import org.whispersystems.signalservice.api.messages.multidevice.ReadMessage
import org.whispersystems.signalservice.api.messages.multidevice.SentTranscriptMessage
import org.whispersystems.signalservice.api.messages.multidevice.SignalServiceSyncMessage
import org.whispersystems.signalservice.api.push.SignalServiceAddress
import java.io.IOException
import java.util.Optional
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Everything that leaves this phone. Text messages wait in the `message` table as "sending"
 * and go out oldest first, so one written offline or before a crash is still sent later.
 * Ported piecewise from signal-cli 0.14.10 `helper/SendHelper.java`.
 */
internal class SendQueue(
    private val deps: SignalDependencies,
    private val db: StillDatabase,
    private val store: AccountStore,
    private val power: Power,
) {
    private val dao = db.chats()
    private val executor = Executors.newSingleThreadScheduledExecutor { Thread(it, "still-send").apply { isDaemon = true } }
    private var failures = 0
    private val self get() = deps.credentials.aci.toString()

    /** The user removed the switch that used to gate this (2026-11-21): Still sends like any Signal app. */
    private val contactsAllowed = true

    /** Seconds the server asked us to hold off, from its last "too many requests" answer. */
    private var askedToWait = 0L

    fun kick() { executor.execute(::drain) }

    /**
     * True when the server said "too many requests" (429): the message is fine and goes out
     * later, so it stays queued instead of being marked as not sent.
     */
    private fun slowDown(e: NonSuccessfulResponseCodeException): Boolean {
        if (e.code != 429) return false
        // Which request was limited (fetching keys or the send itself): method names only.
        val where = e.stackTrace.take(8).joinToString(" < ") { it.className.substringAfterLast('.') + "." + it.methodName }
        Log.w(TAG, "Rate limited at $where")
        val asked = (e as? RateLimitException)?.retryAfterMilliseconds?.orElse(null) ?: 0L
        askedToWait = (asked / 1000 + 1).coerceIn(30, 3600)
        return true
    }

    private fun drain() {
        dao.failOldSends(System.currentTimeMillis() - GIVE_UP_AFTER)
        dao.pruneSendLog(System.currentTimeMillis() - GIVE_UP_AFTER)
        for (row in dao.pendingSends()) {
            power.hold(60_000)
            val status = try {
                val thread = dao.thread(row.threadId)
                if (thread == null) MessageRow.STATUS_FAILED else deliver(thread, text(row))
            } catch (e: NonSuccessfulResponseCodeException) {
                Log.w(TAG, "Send refused: ${e.code}")
                if (slowDown(e)) null else MessageRow.STATUS_FAILED
            } catch (e: IOException) {
                // An upload that could not reach the server: try again later.
                Log.w(TAG, "Send waiting: ${e.javaClass.simpleName}")
                null
            } catch (e: Exception) {
                Log.w(TAG, "Send error: ${e.javaClass.simpleName}")
                MessageRow.STATUS_FAILED
            } finally {
                power.release()
            }
            if (status == null) {
                // No network: leave it queued and look again later, backing off to ten minutes.
                val wait = maxOf((15L shl failures.coerceAtMost(5)).coerceAtMost(600), askedToWait)
                askedToWait = 0
                failures++
                executor.schedule(::drain, wait, TimeUnit.SECONDS)
                return
            }
            failures = 0
            dao.setStatus(row.id, status)
        }
    }

    private fun base(timestamp: Long, expiresIn: Int): SignalServiceDataMessage.Builder =
        SignalServiceDataMessage.newBuilder()
            .withTimestamp(timestamp)
            .withExpiration(expiresIn)
            .apply { store.bytes(AccountStore.PROFILE_KEY)?.let(::withProfileKey) }

    private fun text(row: MessageRow): SignalServiceDataMessage.Builder = base(row.sentAt, row.expiresIn).apply {
        row.body?.let(::withBody)
        val files = dao.attachmentsOf(row.id)
        if (files.isNotEmpty()) withAttachments(files.map(::pointer))
        val author = row.quoteAuthorId?.let(dao::recipient)?.aci?.let(ACI::parseOrNull)
        if (row.quoteSentAt != null && author != null) {
            withQuote(
                SignalServiceDataMessage.Quote(
                    row.quoteSentAt, author, row.quoteText.orEmpty(), emptyList(), emptyList(),
                    SignalServiceDataMessage.Quote.Type.NORMAL, emptyList(),
                ),
            )
        }
    }

    /** Uploads a file once; a retry of the message reuses what is already on Signal's servers. */
    private fun pointer(file: AttachmentRow): SignalServiceAttachmentPointer {
        file.pointer?.let { return AttachmentPointerUtil.createSignalAttachmentPointer(AttachmentPointer.ADAPTER.decode(it)) }
        val source = File(file.localPath ?: throw IOException("Attachment has no file"))
        val spec = deps.cdnService.getResumableUploadSpecBlocking(
            AttachmentCipherStreamUtil.getCiphertextLength(PaddingInputStream.getPaddedSize(source.length())),
        )
        return source.inputStream().use { input ->
            val stream = SignalServiceAttachment.newStreamBuilder()
                .withStream(input)
                .withContentType(file.contentType)
                .withLength(source.length())
                .withFileName(file.fileName)
                .withVoiceNote(file.voiceNote)
                .withUploadTimestamp(System.currentTimeMillis())
                .withResumableUploadSpec(spec)
                .withUuid(UUID.randomUUID())
                .build()
            deps.messageSender.uploadAttachment(stream).also {
                dao.setAttachmentPointer(file.id, AttachmentPointerUtil.createAttachmentPointer(it).encode())
            }
        }
    }

    private var certificate: SenderCertificate? = null

    /**
     * Sealed sender hides from Signal's servers who a message is from. It needs a short-lived
     * certificate for us and an access key derived from the recipient's profile key; without
     * either the message goes the ordinary way. From signal-cli `UnidentifiedAccessHelper`.
     */
    fun sealed(aci: String): SealedSenderAccess? = try {
        val profileKey = dao.recipientByAci(aci)?.profileKey
        if (profileKey == null) null else {
            val current = certificate?.takeIf { System.currentTimeMillis() < it.expiration - 3600_000L }
                // The variant without our phone number in it.
                ?: SenderCertificate(deps.certificateApi.getSenderCertificateForPhoneNumberPrivacy().successOrThrow()).also { certificate = it }
            SealedSenderAccess.forIndividual(UnidentifiedAccess(UnidentifiedAccess.deriveAccessKeyFrom(ProfileKey(profileKey)), current.serialized, false))
        }
    } catch (e: Throwable) {
        Log.w(TAG, "No sealed sender for this message: ${e.javaClass.simpleName}")
        null
    }

    /**
     * Sends one data message to whoever the conversation is with: ourselves, one person, or
     * each member of a group. Returns the new status, or null to try again later.
     */
    private fun deliver(thread: ThreadRow, builder: SignalServiceDataMessage.Builder, signedChange: ByteArray? = null, editOf: Long? = null): Int? {
        thread.groupMasterKey?.let { return group(builder, thread.id, it, thread.groupRevision, signedChange, editOf) }
        val aci = thread.recipientId?.let(dao::recipient)?.aci ?: return MessageRow.STATUS_FAILED
        val message = builder.build()
        if (aci == self) return noteToSelf(message, editOf)
        if (!contactsAllowed) return MessageRow.STATUS_FAILED

        val address = SignalServiceAddress(ACI.parseOrThrow(aci))
        return try {
            val access = sealed(aci)
            val events = SignalServiceMessageSender.IndividualSendEvents.EMPTY
            val result = if (editOf == null) deps.messageSender.sendDataMessage(address, access, ContentHint.RESENDABLE, message, events, true, false)
            else deps.messageSender.sendEditMessage(address, access, ContentHint.RESENDABLE, message, events, true, editOf)
            Log.i(TAG, "Message: success=${result.isSuccess} sealed=${result.success?.isUnidentified} network=${result.isNetworkFailure} identity=${result.identityFailure != null}")
            remember(result, null)
            when {
                result.isSuccess -> MessageRow.STATUS_SENT
                result.isNetworkFailure -> null
                else -> {
                    result.identityFailure?.identityKey?.let { remember(aci, it) }
                    MessageRow.STATUS_FAILED
                }
            }
        } catch (e: UntrustedIdentityException) {
            Log.i(TAG, "Message held back: the recipient's safety number changed")
            e.identityKey?.let { remember(aci, it) }
            MessageRow.STATUS_FAILED
        } catch (e: NonSuccessfulResponseCodeException) {
            Log.w(TAG, "Message refused by the server: ${e.code}")
            if (slowDown(e)) null else MessageRow.STATUS_FAILED
        } catch (e: IOException) {
            Log.w(TAG, "Message not sent yet: ${e.javaClass.simpleName}")
            null
        }
    }

    /**
     * A group message goes to each member separately, each with their own session ("fan-out").
     * Signal's faster sender-key path needs group send endorsements and is not ported yet.
     */
    private fun group(builder: SignalServiceDataMessage.Builder, threadId: Long, masterKey: ByteArray, revision: Int, signedChange: ByteArray?, editOf: Long?, also: List<String> = emptyList()): Int? {
        if (!contactsAllowed) return MessageRow.STATUS_FAILED
        // [also]: people a change just removed still need to hear of it.
        val acis = (dao.memberAcis(threadId) + also).distinct().filter { it != self }
        val members = acis.map { SignalServiceAddress(ACI.parseOrThrow(it)) }
        // No member list means the group state has not been fetched; sending blind is not safe.
        if (members.isEmpty() || revision < 0) return MessageRow.STATUS_FAILED
        val context = SignalServiceGroupV2.newBuilder(GroupMasterKey(masterKey)).withRevision(revision)
        if (signedChange != null) context.withSignedGroupChange(signedChange)
        val message = builder.asGroupMessage(context.build()).build()
        return try {
            val access = acis.map(::sealed)
            val events = SignalServiceMessageSender.LegacyGroupEvents.EMPTY
            val results = if (editOf == null) deps.messageSender.sendDataMessage(members, access, false, ContentHint.RESENDABLE, message, events, null, null, true)
            else deps.messageSender.sendEditMessage(members, access, false, ContentHint.RESENDABLE, message, events, null, null, true, editOf)
            val groupId = try { GroupSecretParams.deriveFromMasterKey(GroupMasterKey(masterKey)).publicParams.groupIdentifier.serialize() } catch (e: Exception) { null }
            results.forEach { remember(it, groupId) }
            val sent = results.count { it.isSuccess }
            Log.i(TAG, "Group message: $sent of ${results.size} members reached")
            when {
                sent > 0 -> MessageRow.STATUS_SENT
                results.all { it.isNetworkFailure } -> null
                else -> MessageRow.STATUS_FAILED
            }
        } catch (e: UntrustedIdentityException) {
            MessageRow.STATUS_FAILED
        } catch (e: NonSuccessfulResponseCodeException) {
            Log.w(TAG, "Group message refused by the server: ${e.code}")
            if (slowDown(e)) null else MessageRow.STATUS_FAILED
        } catch (e: IOException) {
            null
        }
    }

    /**
     * Note to Self is a "sent transcript" sync message addressed to our own account: it goes
     * to this account's other devices and to nobody else. From `sendSelfMessage`.
     */
    private fun noteToSelf(message: SignalServiceDataMessage, editOf: Long? = null): Int? {
        val self = SignalServiceAddress(deps.credentials.aci, deps.credentials.e164)
        val transcript = SentTranscriptMessage(
            Optional.of(self), message.timestamp, if (editOf == null) Optional.of(message) else Optional.empty(), message.expiresInSeconds.toLong(),
            mapOf(self.serviceId to true), false, Optional.empty(), emptySet(),
            if (editOf == null) Optional.empty() else Optional.of(SignalServiceEditMessage(editOf, message)),
        )
        return try {
            val result = deps.messageSender.sendSyncMessage(SignalServiceSyncMessage.forSentTranscript(transcript))
            Log.i(TAG, "Note to Self: success=${result.isSuccess} network=${result.isNetworkFailure}")
            when {
                result.isSuccess -> MessageRow.STATUS_SENT
                result.isNetworkFailure -> null
                else -> MessageRow.STATUS_FAILED
            }
        } catch (e: IOException) {
            null
        }
    }

    /** True if a message to this conversation may leave the phone at all. */
    private fun mayWrite(thread: ThreadRow): Boolean =
        contactsAllowed || (thread.recipientId?.let(dao::recipient)?.aci == self)

    /**
     * Adds our reaction to a message, replaces it, or takes it away if [emoji] is the one
     * already there. Shown at once; not retried if it cannot be sent.
     */
    fun react(messageId: Long, emoji: String) = executor.execute {
        val target = dao.message(messageId) ?: return@execute
        val thread = dao.thread(target.threadId) ?: return@execute
        if (!mayWrite(thread)) return@execute
        val author = ACI.parseOrNull(target.senderId?.let(dao::recipient)?.aci ?: self) ?: return@execute
        val remove = dao.ownReaction(messageId) == emoji
        if (remove) dao.removeReaction(messageId, 0) else dao.putReaction(ReactionRow(messageId, 0, emoji))
        once("Reaction") {
            deliver(thread, base(System.currentTimeMillis(), thread.expireTimer).withReaction(SignalServiceDataMessage.Reaction(emoji, remove, author, target.sentAt)))
        }
    }

    /** Deletes one of our own messages for everybody in the conversation. */
    fun deleteForEveryone(messageId: Long, deleteLocally: (Long) -> Unit) = executor.execute {
        val target = dao.message(messageId) ?: return@execute
        val thread = dao.thread(target.threadId) ?: return@execute
        if (target.senderId != null || !mayWrite(thread)) return@execute
        deleteLocally(messageId)
        once("Delete") {
            deliver(thread, base(System.currentTimeMillis(), 0).withRemoteDelete(SignalServiceDataMessage.RemoteDelete(target.sentAt)))
        }
    }

    /** Replaces the text of one of our own messages for everyone. [applyLocally] stores the new text here. */
    fun edit(messageId: Long, text: String, applyLocally: (Long, String, Long) -> Unit) = executor.execute {
        val target = dao.message(messageId) ?: return@execute
        val thread = dao.thread(target.threadId) ?: return@execute
        if (target.senderId != null) return@execute
        val now = System.currentTimeMillis()
        applyLocally(messageId, text, now)
        once("Edit") { deliver(thread, base(now, target.expiresIn).withBody(text), editOf = target.revisionAt ?: target.sentAt) }
    }

    /** Changes the disappearing-message timer of a conversation with one person. */
    fun setTimer(threadId: Long, seconds: Int) = executor.execute {
        val thread = dao.thread(threadId) ?: return@execute
        val recipient = thread.recipientId?.let(dao::recipient) ?: return@execute
        if (!mayWrite(thread)) return@execute
        val version = recipient.expireTimerVersion + 1
        dao.raiseTimerVersion(recipient.id, version)
        dao.setExpireTimer(threadId, seconds)
        once("Timer change") {
            deliver(thread, base(System.currentTimeMillis(), seconds).asExpirationUpdate().withExpireTimerVersion(version))
        }
    }

    /** Tells a group's members about a change that the groups server has already accepted. */
    fun announceGroupChange(threadId: Long, signedChange: ByteArray?, formerMembers: List<String>) = executor.execute {
        val thread = dao.thread(threadId) ?: return@execute
        val key = thread.groupMasterKey ?: return@execute
        once("Group update") {
            group(base(System.currentTimeMillis(), thread.expireTimer), threadId, key, thread.groupRevision.coerceAtLeast(0), signedChange, null, formerMembers)
        }
    }

    /** Keeps what was sent, so it can be sent again if the recipient's app asks. */
    private fun remember(result: SendMessageResult, groupId: ByteArray?) {
        val content = result.success?.content?.orElse(null) ?: return
        val timestamp = content.dataMessage?.timestamp ?: content.editMessage?.dataMessage?.timestamp ?: return
        dao.putSendLog(SendLogRow(result.address.serviceId.toString(), timestamp, content.encode(), groupId, System.currentTimeMillis()))
    }

    /**
     * A recipient's app could not decrypt something we sent and asks for it again. Sets the
     * session aside if it is the one that failed, then resends from the log.
     * From signal-cli `IncomingMessageHandler.handleDecryptionErrorMessage`.
     */
    fun answerResendRequest(senderAci: String, senderDevice: Int, request: ByteArray) = executor.execute {
        try {
            val error = DecryptionErrorMessage(request)
            if (error.deviceId != deps.credentials.deviceId) return@execute
            val address = SignalProtocolAddress(senderAci, senderDevice)
            error.ratchetKey.orElse(null)?.let { key ->
                deps.sessionLock.acquire().use {
                    val store = deps.dataStore.aci()
                    if (store.containsSession(address) && store.loadSession(address).currentRatchetKeyMatches(key)) store.archiveSession(address)
                }
            }
            val entry = dao.sendLog(senderAci, error.timestamp)
            if (entry == null) {
                Log.i(TAG, "Resend asked for a message no longer in the log")
                return@execute
            }
            val result = deps.messageSender.resendContent(
                SignalServiceAddress(ACI.parseOrThrow(senderAci)), sealed(senderAci), error.timestamp,
                Content.ADAPTER.decode(entry.content), ContentHint.RESENDABLE, Optional.ofNullable(entry.groupId), true,
            )
            Log.i(TAG, "Resent a message on request: success=${result.isSuccess}")
        } catch (e: Exception) {
            Log.w(TAG, "Could not answer a resend request: ${e.javaClass.simpleName}")
        }
    }

    /** Tells a sender their message reached this device: their second tick. Every Signal device does this. */
    fun delivered(senderAci: String, sentAt: Long) = executor.execute {
        try {
            if (dao.threadIdByAci(senderAci)?.let(dao::thread)?.blocked == true) return@execute
            val sender = ACI.parseOrNull(senderAci) ?: return@execute
            deps.messageSender.sendReceipt(
                SignalServiceAddress(sender), sealed(senderAci),
                SignalServiceReceiptMessage(SignalServiceReceiptMessage.Type.DELIVERY, listOf(sentAt), System.currentTimeMillis()), false,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Delivery receipt failed: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Asks the sender of a message we could not decrypt to send it again, after setting the
     * broken session aside. From signal-cli `SendRetryMessageRequestAction` and `RenewSessionAction`.
     */
    fun requestResend(error: ProtocolException, envelope: Envelope) = executor.execute {
        try {
            val sender = ACI.parseOrNull(error.sender) ?: return@execute
            if (sender.toString() == self && error.senderDevice == deps.credentials.deviceId) return@execute
            if (dao.threadIdByAci(sender.toString())?.let(dao::thread)?.blocked == true) return@execute
            deps.sessionLock.acquire().use { deps.dataStore.aci().archiveSession(SignalProtocolAddress(sender.toString(), error.senderDevice)) }
            val sealedContent = error.unidentifiedSenderMessageContent.orElse(null)
            val content = sealedContent?.content ?: envelope.content?.toByteArray() ?: return@execute
            val type = sealedContent?.type ?: when (envelope.type) {
                Envelope.Type.PREKEY_MESSAGE -> CiphertextMessage.PREKEY_TYPE
                Envelope.Type.UNIDENTIFIED_SENDER -> CiphertextMessage.SENDERKEY_TYPE
                Envelope.Type.PLAINTEXT_CONTENT -> CiphertextMessage.PLAINTEXT_CONTENT_TYPE
                else -> CiphertextMessage.WHISPER_TYPE
            }
            val message = DecryptionErrorMessage.forOriginalMessage(content, type, envelope.clientTimestamp ?: 0, error.senderDevice)
            val result = deps.messageSender.sendRetryReceipt(SignalServiceAddress(sender), sealed(sender.toString()), error.groupId, message)
            Log.i(TAG, "Resend request: success=${result.isSuccess}")
        } catch (e: Exception) {
            Log.w(TAG, "Resend request failed: ${e.javaClass.simpleName}")
        }
    }

    private fun once(what: String, send: () -> Int?) {
        try {
            val status = send()
            if (status != MessageRow.STATUS_SENT) Log.w(TAG, "$what was not sent")
        } catch (e: Exception) {
            Log.w(TAG, "$what failed: ${e.javaClass.simpleName}")
        }
    }

    /** Tells one person we are typing, or have stopped. Never sent to groups or without the user's switches. */
    fun typing(threadId: Long, started: Boolean) = executor.execute {
        if (!contactsAllowed || store.string(TYPING) == "false") return@execute
        val thread = dao.thread(threadId) ?: return@execute
        val aci = thread.recipientId?.let(dao::recipient)?.aci?.takeIf { it != self } ?: return@execute
        try {
            val action = if (started) SignalServiceTypingMessage.Action.STARTED else SignalServiceTypingMessage.Action.STOPPED
            deps.messageSender.sendTyping(
                listOf(SignalServiceAddress(ACI.parseOrThrow(aci))), listOf(sealed(aci)),
                SignalServiceTypingMessage(action, System.currentTimeMillis(), Optional.empty()), null,
            )
        } catch (e: Exception) {
            // Typing indicators are best effort.
        }
    }

    /**
     * Puts a failed message back in the queue. If it failed because the recipient's safety
     * number changed, retrying is the user's approval of the new one.
     */
    fun retry(messageId: Long, threadId: Long) = executor.execute {
        val aci = dao.thread(threadId)?.recipientId?.let(dao::recipient)?.aci
        if (aci != null) {
            store.bytes(PENDING_IDENTITY + aci)?.let { key ->
                deps.sessionLock.acquire().use { deps.dataStore.aci().saveIdentity(SignalProtocolAddress(aci, 1), IdentityKey(key)) }
                db.keyValue().remove(PENDING_IDENTITY + aci)
            }
        }
        dao.retry(messageId)
        drain()
    }

    private fun remember(aci: String, key: IdentityKey) = store.putAll(mapOf(PENDING_IDENTITY + aci to key.serialize()))

    /**
     * Tells this account's other devices what was read here, so they stop showing it as unread.
     * The senders are told too only if the user allows traffic to contacts and has read
     * receipts switched on.
     */
    fun read(messages: List<UnreadRow>) = executor.execute {
        val read = messages.mapNotNull { row -> row.senderAci?.let(ACI::parseOrNull)?.let { it to row.sentAt } }
        if (read.isEmpty()) return@execute
        try {
            val result = deps.messageSender.sendSyncMessage(SignalServiceSyncMessage.forRead(read.map { ReadMessage(it.first, it.second) }))
            Log.i(TAG, "Read sync for ${read.size} messages: success=${result.isSuccess}")
            val receipts = store.string(READ_RECEIPTS) ?: store.string(AccountStore.READ_RECEIPTS)
            if (!contactsAllowed || receipts != "true") return@execute
            val now = System.currentTimeMillis()
            for ((sender, timestamps) in read.groupBy({ it.first }, { it.second })) {
                deps.messageSender.sendReceipt(
                    SignalServiceAddress(sender), sealed(sender.toString()),
                    SignalServiceReceiptMessage(SignalServiceReceiptMessage.Type.READ, timestamps, now), false,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Read sync failed: ${e.javaClass.simpleName}")
        }
    }

    companion object {
        private const val TAG = "StillSend"
        const val SEND_TO_CONTACTS = "setting.send_to_contacts"
        const val READ_RECEIPTS = "setting.read_receipts"
        const val TYPING = "setting.typing"
        private const val PENDING_IDENTITY = "identity.pending."
        private const val GIVE_UP_AFTER = 24 * 3600_000L
    }
}
