package com.kongeskog.still.bridge

import android.content.Context
import com.kongeskog.still.bridge.db.StillDatabase
import kotlinx.coroutines.CoroutineScope
import org.signal.core.models.ServiceId.ACI
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.fingerprint.NumericFingerprintGenerator
import org.signal.storageservice.storage.protos.groups.GroupChange
import org.whispersystems.signalservice.api.groupsv2.GroupsV2Operations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.kongeskog.still.bridge.db.AttachmentRow
import com.kongeskog.still.bridge.db.ThreadListRow
import kotlin.concurrent.thread

data class ThreadSummary(
    val id: Long, val title: String, val snippet: String, val time: Long,
    val unread: Int, val isGroup: Boolean, val isSelf: Boolean,
    /** Seconds after which messages here disappear; 0 when off. */
    val expireTimer: Int,
    /** For a group: whether this account may change its name and timer. */
    val canEdit: Boolean = false,
    val muted: Boolean = false,
    val pinned: Boolean = false,
    val blocked: Boolean = false,
    val archived: Boolean = false,
)

/** A message found by [SignalClient.search]. */
data class SearchHit(val messageId: Long, val threadId: Long, val title: String, val body: String, val time: Long)

data class Contact(val id: Long, val name: String, val number: String?)

data class Settings(
    val readReceipts: Boolean = true,
    val typingIndicators: Boolean = true,
    val notifications: Boolean = true,
    /** What a notification shows. */
    val preview: Preview = Preview.NameAndMessage,
    val enterSends: Boolean = false,
    val largeText: Boolean = false,
)

enum class Preview { NameAndMessage, NameOnly, Nothing }

/** A person in a group, for its details screen. */
data class Member(val id: Long, val name: String)

enum class MessageStatus { Sending, Sent, Delivered, Read, Failed }

data class ChatMessage(
    val id: Long, val outgoing: Boolean, val sender: String?, val body: String,
    val sentAt: Long, val status: MessageStatus,
    val edited: Boolean = false,
    /** "Author: text" of the message this one replies to. */
    val quote: String? = null,
    /** The emoji people reacted with, space separated. */
    val reactions: String? = null,
    val attachments: List<ChatAttachment> = emptyList(),
    /** A line of news in the timeline (a missed call, a group change), not something anyone wrote. */
    val isEvent: Boolean = false,
)

enum class AttachmentKind { Photo, Voice, File }

/** A file on a message. [path] is null until it has been downloaded. */
data class ChatAttachment(val id: Long, val kind: AttachmentKind, val path: String?, val name: String, val size: Long, val contentType: String)

/** What the app may know about the signed-in account. */
data class AccountInfo(val number: String?, val deviceId: Int)

/**
 * The only door into the Signal layer for the rest of the app. Nothing from
 * `org.whispersystems` or `org.signal` is exposed through it.
 */
class SignalClient private constructor(private val context: Context) {
    init { Logging.install() }

    private val db = StillDatabase.open(context)
    private val store = AccountStore(db)
    private val linking = LinkingManager(db, store)

    private val _account = MutableStateFlow<AccountInfo?>(null)
    /** Null until [load] has run, and while no account is linked. */
    val account: StateFlow<AccountInfo?> = _account

    private val _connection = MutableStateFlow("Not connected")
    /** A short, user-facing description of the connection to Signal. */
    val connection: StateFlow<String> = _connection

    private val processor by lazy {
        EnvelopeProcessor(db, store.aci ?: error("Not linked")).apply {
            // Read on another device: its notification here has done its job.
            onReadElsewhere = { Notifier.cancel(context, it) }
        }
    }
    private val power = Power(context)
    private var receiveLoop: ReceiveLoop? = null
    private var deps: SignalDependencies? = null
    private var directory: Directory? = null
    private var sendQueue: SendQueue? = null
    private var attachments: Attachments? = null
    private val expiryChanged = Channel<Unit>(Channel.CONFLATED)

    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings

    private fun readSettings() = Settings(
        readReceipts = (store.string(SendQueue.READ_RECEIPTS) ?: store.string(AccountStore.READ_RECEIPTS)) == "true",
        typingIndicators = store.string(SendQueue.TYPING) != "false",
        notifications = store.string(NOTIFICATIONS) != "false",
        preview = Preview.entries[(store.int(PREVIEW) ?: 0).coerceIn(0, Preview.entries.lastIndex)],
        enterSends = store.string(ENTER_SENDS) == "true",
        largeText = store.string(LARGE_TEXT) == "true",
    )

    /** Saves the settings. */
    fun update(change: (Settings) -> Settings) {
        scope.launch(Dispatchers.IO) {
            val next = change(readSettings())
            store.putAll(
                mapOf(
                    SendQueue.READ_RECEIPTS to next.readReceipts,
                    SendQueue.TYPING to next.typingIndicators, NOTIFICATIONS to next.notifications,
                    PREVIEW to next.preview.ordinal, ENTER_SENDS to next.enterSends, LARGE_TEXT to next.largeText,
                ),
            )
            _settings.value = next
        }
    }

    /**
     * Makes Still the account's main device; the Signal app is signed out by the server.
     * Returns null on success, after which the process must be restarted, or a reason.
     * Only ever called from the user's own confirmation.
     */
    suspend fun becomeMainDevice(): String? = withContext(Dispatchers.IO) {
        try {
            PrimaryRegistration(db, store).promote()
        } catch (e: Exception) {
            android.util.Log.w("StillRegister", "Registration failed: ${e.javaClass.simpleName}")
            "Something went wrong. Nothing was changed."
        }
    }

    /**
     * True once this build is old enough that Signal may soon stop accepting it. Shown well
     * ahead of the cut-off, because after it no messages arrive until Still is updated.
     */
    val updateDue: Boolean
        get() = java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.parse(SignalEnvironment.LIBRARIES_UPDATED), java.time.LocalDate.now()) >= UPDATE_DUE_AFTER_DAYS

    /** The date this build's Signal libraries were brought up to date, as written in the source. */
    val librariesUpdated: String get() = SignalEnvironment.LIBRARIES_UPDATED

    private var calls: Calls? = null
    private val _call = MutableStateFlow(CallState())
    /** The voice call in progress or ringing; [CallPhase.Idle] when there is none. */
    val call: StateFlow<CallState> = _call

    /** Starts a voice call to the person a conversation is with. The caller must hold the microphone permission. */
    fun startCall(threadId: Long) { calls?.start(threadId) }
    fun acceptCall() {
        // Re-enter the foreground from the visible screen, so the microphone survives the screen going off.
        ConnectionService.start(context)
        calls?.accept()
    }
    fun endCall() { calls?.hangUp() }
    fun setCallMuted(muted: Boolean) { calls?.setMuted(muted) }
    fun setCallSpeaker(on: Boolean) { calls?.setSpeaker(on) }

    private val _history = MutableStateFlow<String?>(null)
    /** What the import of earlier messages is doing, or null when it is not running. */
    val history: StateFlow<String?> = _history

    private val _typing = MutableStateFlow<Set<Long>>(emptySet())
    /** Conversations in which someone is typing right now. */
    val typing: StateFlow<Set<Long>> = _typing
    private val typingJobs = HashMap<Long, Job>()

    @Synchronized
    private fun onTyping(senderAci: String, groupId: ByteArray?, started: Boolean) {
        val dao = db.chats()
        val thread = if (groupId != null) dao.threadByGroup(java.util.Base64.getEncoder().encodeToString(groupId))?.id else dao.threadIdByAci(senderAci)
        thread ?: return
        typingJobs.remove(thread)?.cancel()
        if (started) {
            _typing.value = _typing.value + thread
            // The sender repeats "started" every ten seconds; silence means they stopped.
            typingJobs[thread] = scope.launch {
                delay(15_000)
                _typing.value = _typing.value - thread
            }
        } else {
            _typing.value = _typing.value - thread
        }
    }

    /** Reads the stored account and, if linked, starts receiving. Returns true if linked. */
    suspend fun load(): Boolean = withContext(Dispatchers.IO) {
        val linked = store.isLinked
        _account.value = if (linked) AccountInfo(store.number, store.deviceId) else null
        if (linked) startReceiving()
        linked
    }

    @Synchronized
    private fun startReceiving() {
        if (receiveLoop != null) return
        val deps = SignalDependencies(Credentials(store), DataStore(db, store), power).also { deps = it }
        val directory = Directory(deps, db, store, context.cacheDir).also { directory = it }
        val queue = SendQueue(deps, db, store, power).also { sendQueue = it }
        calls = Calls(context, deps, db, store, power, queue::sealed) { _call.value = it }
        val files = Attachments(deps, db, java.io.File(context.filesDir, "attachments"), context.cacheDir).also { attachments = it }
        val loop = ReceiveLoop(
            deps, db, store, processor,
            onNewMessage = {
                _typing.value = _typing.value - it.threadId
                notify(it)
                directory.refresh()
            },
            onState = {
                _connection.value = it
                // Anything written while offline goes out now.
                if (it == "Connected") queue.kick()
            },
            onCaughtUp = {
                directory.refresh()
                // Keys are topped up and rotated at start and twice a day after: the process can live for weeks.
                scope.launch(Dispatchers.IO) {
                    while (true) {
                        try {
                            PreKeyManager(deps, db, store).refreshIfNecessary()
                        } catch (e: Exception) {
                            android.util.Log.w("StillKeys", "Prekey refresh failed: ${e.javaClass.simpleName}")
                        }
                        delay(12 * 3600_000L)
                    }
                }
            },
            onContacts = directory::importContacts,
            power = power,
            onTyping = ::onTyping,
            onFiled = files::fetchNew,
            onUndecryptable = queue::requestResend,
            onDelivered = queue::delivered,
            onCall = { sender, device, message, age -> calls?.received(sender, device, message, age) },
            onResendRequest = queue::answerResendRequest,
        )
        scope.launch(Dispatchers.IO) { expireLoop() }
        // Present only right after a link in which the user agreed to transfer history.
        thread(name = "still-history", isDaemon = true) {
            HistoryImport(deps, db, store, context.cacheDir) { _history.value = it }.runIfPending()
            directory.refresh()
        }
        _settings.value = readSettings()
        receiveLoop = loop
        thread(name = "still-receive", isDaemon = true) {
            try {
                loop.run()
            } catch (t: Throwable) {
                android.util.Log.e("StillReceive", "Receive loop ended", t)
                _connection.value = "Not connected"
            }
        }
    }

    /** Conversations, newest first. */
    fun threads(archived: Boolean = false): Flow<List<ThreadSummary>> = db.chats().threads(archived).map { rows -> rows.map(::summary) }.flowOn(Dispatchers.IO)

    /** How many conversations are archived. */
    fun archivedCount(): Flow<Int> = db.chats().archivedCount().flowOn(Dispatchers.IO)

    fun setArchived(threadId: Long, archived: Boolean) { scope.launch(Dispatchers.IO) { db.chats().setArchived(threadId, archived) } }

    /** Creates a group with the given contacts and returns its conversation, or null if Signal refused. */
    suspend fun createGroup(name: String, contactIds: List<Long>): Long? = withContext(Dispatchers.IO) {
        val thread = directory?.createGroup(name, contactIds) ?: return@withContext null
        // The members learn of the group from this first, empty message.
        sendQueue?.announceGroupChange(thread, null, emptyList())
        thread
    }

    /** One conversation, including an empty one that is not in the list yet. */
    fun thread(id: Long): Flow<ThreadSummary?> = db.chats().threadSummary(id).map { it?.let(::summary) }.flowOn(Dispatchers.IO)

    private fun summary(row: ThreadListRow): ThreadSummary {
        val isSelf = row.aci != null && row.aci == store.aci
        return ThreadSummary(
            id = row.id,
            title = when {
                row.groupId != null -> row.title ?: "Group"
                isSelf -> "Note to Self"
                else -> row.name ?: row.e164 ?: "Unknown contact"
            },
            snippet = row.snippet.orEmpty(),
            time = row.lastMessageAt,
            unread = row.unread,
            isGroup = row.groupId != null,
            isSelf = isSelf,
            expireTimer = row.expireTimer,
            canEdit = row.canEdit,
            muted = row.muted,
            pinned = row.pinned,
            blocked = row.blocked,
            archived = row.archived,
        )
    }

    /** Everyone Still has a name for, by name. */
    fun contacts(): Flow<List<Contact>> = flow {
        // Built inside the flow: reading our own id touches the database, which must not happen on the main thread.
        emitAll(db.chats().contacts(store.aci.orEmpty()).map { rows -> rows.map { Contact(it.id, it.name.orEmpty(), it.e164) } })
    }.flowOn(Dispatchers.IO)

    /** The conversation with a contact, created empty if there is none. */
    suspend fun threadWith(contactId: Long): Long = withContext(Dispatchers.IO) { processor.threadWith(contactId) }

    /** Names of a group's members, for its header. */
    suspend fun members(threadId: Long): List<String> = withContext(Dispatchers.IO) {
        db.chats().members(threadId).map { it.name ?: it.e164 ?: "Unknown" }
    }

    /** The newest [limit] messages of a conversation, oldest first. */
    fun messages(threadId: Long, limit: Int = 60): Flow<List<ChatMessage>> =
        db.chats().messages(threadId, limit).combine(db.chats().attachments(threadId)) { rows, files ->
            val self = store.aci
            val byMessage = files.groupBy { it.messageId }
            rows.map { row ->
                val attached = byMessage[row.id].orEmpty().map {
                    val kind = when {
                        it.voiceNote -> AttachmentKind.Voice
                        it.contentType.startsWith("image/") -> AttachmentKind.Photo
                        else -> AttachmentKind.File
                    }
                    ChatAttachment(it.id, kind, it.localPath, it.fileName ?: kind.name, it.size, it.contentType)
                }
                ChatMessage(
                    id = row.id,
                    outgoing = row.senderId == null,
                    sender = if (row.senderId == null) null else row.senderName ?: row.senderE164 ?: "Unknown",
                    body = row.body.orEmpty(),
                    sentAt = row.sentAt,
                    status = MessageStatus.entries[row.status.coerceIn(0, MessageStatus.entries.lastIndex)],
                    edited = row.edited,
                    quote = row.quoteText?.let { "${if (row.quoteAuthorAci == self) "You" else row.quoteAuthorName ?: "Unknown"}: $it" },
                    reactions = row.reactions,
                    attachments = attached,
                    isEvent = row.kind == 1,
                )
            }
        }.flowOn(Dispatchers.IO)

    /** Downloads an attachment that did not come down on its own. */
    fun download(attachmentId: Long) { attachments?.fetch(attachmentId) }

    /**
     * Queues a file for a conversation. [path] must be a file Still owns, under its files
     * directory; it is deleted with the message.
     */
    suspend fun sendFile(threadId: Long, path: String, contentType: String, name: String?, voiceNote: Boolean) = withContext(Dispatchers.IO) {
        processor.outgoing(
            threadId, null,
            file = AttachmentRow(messageId = 0, pointer = null, contentType = contentType, fileName = name, size = java.io.File(path).length(), voiceNote = voiceNote, localPath = path),
        )
        sendQueue?.kick()
    }

    /**
     * Marks a conversation read here and on this account's other devices, and starts the
     * timer of any disappearing message in it.
     */
    suspend fun markRead(threadId: Long) = withContext(Dispatchers.IO) {
        val unread = db.chats().unreadMessages(threadId)
        db.runInTransaction {
            db.chats().markMessagesRead(threadId, System.currentTimeMillis())
            db.chats().markThreadRead(threadId)
        }
        Notifier.cancel(context, threadId)
        if (unread.isNotEmpty()) {
            sendQueue?.read(unread)
            expiryChanged.trySend(Unit)
        }
    }

    /** Deletes disappearing messages when their time is up, sleeping until the next one is due. */
    private suspend fun expireLoop() {
        val dao = db.chats()
        while (true) {
            val now = System.currentTimeMillis()
            val threads = dao.threadsWithExpired(now)
            if (threads.isNotEmpty()) {
                db.runInTransaction {
                    dao.deleteExpired(now)
                    processor.cleanUp()
                    for (id in threads) {
                        val last = dao.lastMessage(id)
                        dao.setSnippet(id, last?.body?.take(120) ?: if ((last?.attachmentCount ?: 0) > 0) "Attachment" else "")
                    }
                }
            }
            val wait = ((dao.nextExpiry() ?: Long.MAX_VALUE) - System.currentTimeMillis()).coerceIn(1_000, 3600_000)
            withTimeoutOrNull(wait) { expiryChanged.receive() }
        }
    }

    /**
     * The conversation on screen, or [LIST_VISIBLE] for the list, or null when Still is not
     * visible. A message for what the user is already looking at raises no notification.
     */
    @Volatile var visibleThread: Long? = null

    private fun notify(message: NewMessage) {
        val visible = visibleThread
        if (visible == message.threadId || visible == LIST_VISIBLE) {
            android.util.Log.i("StillNotify", "New message shown on screen; no notification")
            return
        }
        val settings = readSettings()
        android.util.Log.i("StillNotify", "New message: visible=${visible != null} enabled=${settings.notifications}")
        if (!settings.notifications) return
        val row = db.chats().threadForNotification(message.threadId) ?: return
        if (row.muted) return
        val sender = db.chats().lastSenderName(message.threadId) ?: "Unknown contact"
        val isGroup = row.groupId != null
        val title = if (isGroup) row.title ?: "Group" else sender
        when (settings.preview) {
            Preview.NameAndMessage -> Notifier.message(context, message.threadId, title, sender, isGroup, message.body, System.currentTimeMillis())
            Preview.NameOnly -> Notifier.message(context, message.threadId, title, sender, isGroup, "New message", System.currentTimeMillis())
            Preview.Nothing -> Notifier.message(context, message.threadId, "Still", "Still", false, "New message", System.currentTimeMillis())
        }
    }

    /** Members of a group, by name. Empty for a conversation with one person. */
    suspend fun groupMembers(threadId: Long): List<Member> = withContext(Dispatchers.IO) {
        db.chats().members(threadId).map { Member(it.id, it.name ?: it.e164 ?: "Unknown") }
    }

    /** Adds, changes or removes this account's reaction to a message. */
    fun react(messageId: Long, emoji: String) { sendQueue?.react(messageId, emoji) }

    /** Deletes one of our messages for everyone in the conversation. */
    fun deleteForEveryone(messageId: Long) { sendQueue?.deleteForEveryone(messageId, processor::deleteLocally) }

    /** Replaces the text of one of our own messages, here and for everyone it was sent to. */
    fun edit(messageId: Long, text: String) { sendQueue?.edit(messageId, text, processor::editLocally) }

    /** Stops or resumes notifications for one conversation. */
    fun setMuted(threadId: Long, muted: Boolean) { scope.launch(Dispatchers.IO) { db.chats().setMuted(threadId, muted) } }

    /** Deletes every message of a conversation from this phone. Nobody else is affected. */
    fun clearConversation(threadId: Long) { scope.launch(Dispatchers.IO) { processor.clear(threadId) } }

    /** Deletes a message from this phone only. */
    fun deleteForMe(messageId: Long) { scope.launch(Dispatchers.IO) { processor.deleteLocally(messageId) } }

    /** Sets the disappearing-message timer of a conversation; 0 turns it off. In a group this needs [ThreadSummary.canEdit]. */
    fun setTimer(threadId: Long, seconds: Int) {
        scope.launch(Dispatchers.IO) {
            if (db.chats().thread(threadId)?.groupMasterKey == null) sendQueue?.setTimer(threadId, seconds)
            else changeGroup(threadId) { it.createModifyGroupTimerChange(seconds) }
        }
    }

    /** Renames a group. Needs [ThreadSummary.canEdit]. */
    fun renameGroup(threadId: Long, name: String) {
        changeGroup(threadId) { it.createModifyGroupTitle(name) }
    }

    private fun changeGroup(threadId: Long, after: () -> Unit = {}, change: (GroupsV2Operations.GroupOperations) -> GroupChange.Actions.Builder) {
        directory?.changeGroup(threadId, change) { signed, former ->
            sendQueue?.announceGroupChange(threadId, signed, former)
            after()
        }
    }

    /** Removes someone from a group. Needs [ThreadSummary.canEdit]. */
    fun removeMember(threadId: Long, memberId: Long) {
        scope.launch(Dispatchers.IO) {
            val aci = db.chats().recipient(memberId)?.aci?.let(ACI::parseOrNull) ?: return@launch
            changeGroup(threadId) { it.createRemoveMembersChange(setOf(aci), false, emptyList()) }
        }
    }

    /** Adds a contact to a group, or invites them if their profile cannot be confirmed. Needs [ThreadSummary.canEdit]. */
    fun addMember(threadId: Long, contactId: Long) {
        scope.launch(Dispatchers.IO) {
            val candidate = directory?.candidate(contactId) ?: return@launch
            val self = ACI.parseOrThrow(store.aci ?: return@launch)
            changeGroup(threadId) { it.createModifyGroupMembershipChange(setOf(candidate), emptySet(), self).sourceUserId(self.toByteString()) }
        }
    }

    /** Leaves a group. The conversation stays, with a line saying so. */
    fun leaveGroup(threadId: Long) {
        scope.launch(Dispatchers.IO) {
            val self = ACI.parseOrThrow(store.aci ?: return@launch)
            changeGroup(threadId, after = {
                directory?.removeMembers(threadId)
                processor.note(threadId, "You left the group")
            }) { it.createLeaveAndPromoteMembersToAdmin(self, emptyList()) }
        }
    }

    fun setPinned(threadId: Long, pinned: Boolean) { scope.launch(Dispatchers.IO) { db.chats().setPinned(threadId, pinned) } }

    /** Blocking is kept on this phone: nothing from the conversation is stored or announced here. */
    fun setBlocked(threadId: Long, blocked: Boolean) { scope.launch(Dispatchers.IO) { db.chats().setBlocked(threadId, blocked) } }

    /**
     * The safety number shared with the other person in a conversation: sixty digits that
     * match on both phones if nobody sits in between. Null before any message was exchanged.
     */
    suspend fun safetyNumber(threadId: Long): String? = withContext(Dispatchers.IO) {
        try {
            val self = ACI.parseOrThrow(store.aci ?: return@withContext null)
            val their = db.chats().thread(threadId)?.recipientId?.let { db.chats().recipient(it) }?.aci?.let(ACI::parseOrNull) ?: return@withContext null
            val ours = store.identity(0)?.publicKey ?: return@withContext null
            val theirs = db.sessions().identity(0, their.toString())?.identityKey?.let(::IdentityKey) ?: return@withContext null
            NumericFingerprintGenerator(5200).createFor(2, self.toByteArray(), ours, their.toByteArray(), theirs).displayableFingerprint.displayText
        } catch (e: Exception) {
            null
        }
    }

    /** Messages containing [query], newest first. */
    suspend fun search(query: String): List<SearchHit> = withContext(Dispatchers.IO) {
        val text = query.trim()
        if (text.length < 2) return@withContext emptyList()
        val self = store.aci
        val escaped = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        db.chats().search(escaped).map {
            val title = when {
                it.groupId != null -> it.title ?: "Group"
                it.aci == self -> "Note to Self"
                else -> it.name ?: "Unknown contact"
            }
            SearchHit(it.messageId, it.threadId, title, it.body, it.sentAt)
        }
    }

    /** Tells the other person that the user is typing or has stopped, if their settings allow it. */
    fun typing(threadId: Long, started: Boolean) { sendQueue?.typing(threadId, started) }

    /**
     * Queues [text] for a conversation. It is sent when there is a connection. Anything but
     * Note to Self is refused, and shown as not sent, unless the user has switched on
     * sending to contacts. Groups cannot be written to yet.
     */
    suspend fun send(threadId: Long, text: String, replyTo: Long? = null) = withContext(Dispatchers.IO) {
        processor.outgoing(threadId, text, replyTo)
        expiryChanged.trySend(Unit)
        sendQueue?.kick()
    }

    /** Tries a failed message again. This also accepts a changed safety number, if that was the cause. */
    fun retry(messageId: Long, threadId: Long) { sendQueue?.retry(messageId, threadId) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var linkJob: Job? = null
    private val _linkState = MutableStateFlow<LinkState?>(null)
    /** Progress of the current or last linking attempt; null when none has been started. */
    val linkState: StateFlow<LinkState?> = _linkState

    /**
     * Starts linking to an existing account. It runs for the life of the process, not of
     * any screen: the user has to leave Still to approve the link in Signal, and the link
     * code dies the moment its socket is closed.
     */
    @Synchronized
    fun startLinking(deviceName: String = "Still") {
        if (linkJob?.isActive == true) return
        linkJob = scope.launch {
            linking.link(deviceName).collect {
                if (it is LinkState.Linked) load()
                _linkState.value = it
            }
        }
    }

    @Synchronized
    fun cancelLinking() {
        linkJob?.cancel()
        linkJob = null
        _linkState.value = null
    }

    companion object {
        const val LIST_VISIBLE = -1L
        /** Signal retires a version after about ninety days; warn a month before that. */
        private const val UPDATE_DUE_AFTER_DAYS = 60
        private const val NOTIFICATIONS = "setting.notifications"
        private const val PREVIEW = "setting.preview"
        private const val ENTER_SENDS = "setting.enter_sends"
        private const val LARGE_TEXT = "setting.large_text"
        /** Intent extra naming the conversation a notification should open. */
        const val EXTRA_THREAD = Notifier.EXTRA_THREAD

        @Volatile private var instance: SignalClient? = null

        fun get(context: Context): SignalClient =
            instance ?: synchronized(this) {
                instance ?: SignalClient(context.applicationContext).also { instance = it }
            }
    }
}
