package com.kongeskog.still

import android.content.Intent
import android.os.Bundle
import com.kongeskog.still.bridge.ConnectionService
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.mudita.mmd.ThemeMMD
import com.kongeskog.still.bridge.LinkState
import com.kongeskog.still.bridge.SignalClient
import com.kongeskog.still.bridge.Spike
import com.kongeskog.still.ui.CheckLine
import com.kongeskog.still.ui.LinkScreen
import com.kongeskog.still.ui.LinkUi
import android.text.format.DateUtils
import com.kongeskog.still.bridge.MessageStatus
import com.kongeskog.still.ui.BubbleItem
import com.kongeskog.still.ui.ConversationListScreen
import com.kongeskog.still.ui.ConversationScreen
import com.kongeskog.still.ui.ThreadItem
import com.kongeskog.still.ui.ContactItem
import com.kongeskog.still.ui.ContactsScreen
import com.kongeskog.still.ui.SettingsScreen
import com.kongeskog.still.ui.SettingsUi
import com.kongeskog.still.ui.SettingsActions
import com.kongeskog.still.ui.SettingsPage
import com.kongeskog.still.ui.DetailsScreen
import com.kongeskog.still.ui.DetailsUi
import com.kongeskog.still.ui.TimerScreen
import com.kongeskog.still.ui.RenameScreen
import com.kongeskog.still.ui.CallScreen
import com.kongeskog.still.ui.CallUi
import com.kongeskog.still.bridge.CallPhase
import com.kongeskog.still.ui.ConfirmScreen
import com.kongeskog.still.ui.NewGroupScreen
import com.kongeskog.still.ui.MemberScreen
import com.kongeskog.still.ui.SafetyNumberScreen
import com.kongeskog.still.ui.SearchScreen
import com.kongeskog.still.ui.SearchItem
import com.kongeskog.still.ui.MessageScreen
import com.kongeskog.still.ui.MessageActionsUi
import com.kongeskog.still.bridge.Settings
import com.kongeskog.still.bridge.Preview
import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.produceState
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import com.kongeskog.still.bridge.AttachmentKind
import com.kongeskog.still.ui.AttachmentItem
import com.kongeskog.still.ui.AttachmentType
import com.kongeskog.still.ui.PhotoScreen
import com.kongeskog.still.ui.RecordingUi
import android.content.ClipboardManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.kongeskog.still.ui.SpikeScreen
import com.kongeskog.still.ui.WelcomeScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Page { Welcome, Link, Checks }

/** Messages loaded per step of "Load earlier". */
private const val PAGE = 60
private sealed interface Nav {
    data object Threads : Nav
    data class Chat(val thread: Long) : Nav
    data class Message(val thread: Long, val message: Long) : Nav
    data class Details(val thread: Long) : Nav
    data class Timer(val thread: Long) : Nav
    data class Rename(val thread: Long) : Nav
    data class Member(val thread: Long, val member: Long, val name: String) : Nav
    data class AddMember(val thread: Long) : Nav
    data class Safety(val thread: Long) : Nav
    data object Search : Nav
    data object Archived : Nav
    data object NewGroup : Nav
    data class Photo(val path: String) : Nav
    data object Contacts : Nav
    data class Settings(val page: SettingsPage) : Nav
    data object Checks : Nav
    data object Relink : Nav
    data object MakeMain : Nav
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        takeThread(intent)
        val client = SignalClient.get(this)
        setContent {
            ThemeMMD {
                var loaded by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { client.load(); loaded = true }
                val account by client.account.collectAsState()
                var page by remember { mutableStateOf(Page.Welcome) }

                val current = account
                when {
                    !loaded -> Unit // one blank white frame while the database opens
                    current != null -> Chats(client)
                    page == Page.Link -> Linking(client, onBack = { page = Page.Welcome })
                    page == Page.Checks -> Checks(onBack = { page = Page.Welcome })
                    else -> WelcomeScreen(onLink = { page = Page.Link })
                }
            }
        }
    }

    /** Set when a notification is tapped; the conversation list opens that thread once. */
    private val requestedThread = mutableStateOf<Long?>(null)
    private var resumed = false
    private val recorder by lazy { VoiceRecorder(this) }
    private val player = VoicePlayer()
    /** The conversation a picked file is for; set just before the picker opens. */
    private var attachTo: Long? = null
    private val picker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val thread = attachTo ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) { importUri(this@MainActivity, uri) } ?: return@launch
            SignalClient.get(this@MainActivity).sendFile(thread, file.path, file.contentType, file.name, voiceNote = false)
        }
    }
    private val microphone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) recorder.start()
    }
    /** What to do once the microphone is allowed: answer, or place a call. */
    private var afterMicrophone: (() -> Unit)? = null
    private val callMicrophone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) afterMicrophone?.invoke()
        afterMicrophone = null
    }

    private fun withMicrophone(action: () -> Unit) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) action()
        else {
            afterMicrophone = action
            callMicrophone.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    private val resumes = mutableStateOf(0)
    private var shownThread: Long? = null

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeThread(intent)
    }

    private fun takeThread(intent: Intent?) {
        val id = intent?.getLongExtra(SignalClient.EXTRA_THREAD, 0L) ?: 0L
        if (id > 0) requestedThread.value = id
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        resumes.value++
        publishVisible()
    }

    override fun onPause() {
        // Nothing records or plays once Still is out of sight.
        if (recorder.since.value != null) recorder.cancel()
        player.stop()
        resumed = false
        publishVisible()
        super.onPause()
    }

    /** Tells the client what is on screen, so it does not notify about it. */
    private fun publishVisible() {
        SignalClient.get(this).visibleThread = if (resumed) shownThread ?: SignalClient.LIST_VISIBLE else null
    }

    @Composable
    private fun Chats(client: SignalClient) {
        // The connection must outlive the activity; starting here is allowed because we are in the foreground.
        LaunchedEffect(Unit) { ConnectionService.start(this@MainActivity) }
        val stack = remember { mutableStateListOf<Nav>(Nav.Threads) }
        val push = { nav: Nav -> stack.add(nav); Unit }
        val pop = { if (stack.size > 1) stack.removeAt(stack.lastIndex); Unit }
        val top = stack.last()
        val requested by requestedThread
        LaunchedEffect(requested) {
            requested?.let {
                stack.clear()
                stack.addAll(listOf(Nav.Threads, Nav.Chat(it)))
                requestedThread.value = null
            }
        }
        LaunchedEffect(top) { shownThread = (top as? Nav.Chat)?.thread; publishVisible() }
        BackHandler(enabled = stack.size > 1, onBack = pop)
        val scope = rememberCoroutineScope()
        val settings by client.settings.collectAsState()
        // Read once for the whole activity, so coming back to the list shows it at once
        // instead of an empty screen followed by a second redraw.
        val threads by remember { client.threads() }.collectAsState(emptyList())
        val archived by remember { client.archivedCount() }.collectAsState(0)
        // The message being replied to, per conversation, survives a visit to another screen.
        var reply by remember { mutableStateOf<Pair<Long, Long>?>(null) }
        var editing by remember { mutableStateOf<Pair<Long, Long>?>(null) }

        val call by client.call.collectAsState()
        // A ringing call may show over the lock screen; nothing else may.
        LaunchedEffect(call.phase != CallPhase.Idle) {
            setShowWhenLocked(call.phase != CallPhase.Idle)
            setTurnScreenOn(call.phase != CallPhase.Idle)
        }
        if (call.phase != CallPhase.Idle) {
            val status = when (call.phase) {
                CallPhase.Incoming -> "Incoming call…"
                CallPhase.Calling -> "Calling…"
                CallPhase.Ringing -> "Ringing…"
                CallPhase.Connecting -> "Connecting…"
                else -> call.connectedAt?.let { elapsed(it) } ?: "Connected"
            }
            BackHandler { }
            CallScreen(
                CallUi(call.name, status, call.phase == CallPhase.Incoming, call.muted, call.speaker),
                onAnswer = { withMicrophone(client::acceptCall) },
                onEnd = client::endCall,
                onMute = client::setCallMuted,
                onSpeaker = client::setCallSpeaker,
            )
            return
        }

        when (top) {
            Nav.Archived -> {
                val threads by remember { client.threads(archived = true) }.collectAsState(emptyList())
                ConversationListScreen(
                    threads.map { ThreadItem(it.id, it.title, it.snippet, if (it.time > 0) shortTime(it.time) else "", it.unread) },
                    status = null, onOpen = { push(Nav.Chat(it)) }, onNew = {}, onSettings = {}, onBack = pop,
                )
            }
            Nav.NewGroup -> {
                val contacts by remember { client.contacts() }.collectAsState(emptyList())
                var busy by remember { mutableStateOf(false) }
                NewGroupScreen(contacts.map { ContactItem(it.id, it.name, it.number) }, busy, onBack = pop) { name, members ->
                    busy = true
                    scope.launch {
                        val thread = client.createGroup(name, members)
                        busy = false
                        if (thread != null) { stack.clear(); stack.addAll(listOf(Nav.Threads, Nav.Chat(thread))) }
                    }
                }
            }
            Nav.Threads -> {
                val connection by client.connection.collectAsState()
                val history by client.history.collectAsState()
                ConversationListScreen(
                    archived = archived, onArchived = { push(Nav.Archived) },
                    threads = threads.map { ThreadItem(it.id, it.title, it.snippet, if (it.time > 0) shortTime(it.time) else "", it.unread) },
                    // A connection problem matters most, then a running import, then a build that is getting old.
                    status = connection.takeIf { it != "Connected" } ?: history ?: "Update due".takeIf { client.updateDue },
                    onOpen = { push(Nav.Chat(it)) },
                    onNew = { push(Nav.Contacts) },
                    onSettings = { push(Nav.Settings(SettingsPage.Main)) },
                    onSearch = { push(Nav.Search) },
                )
            }
            is Nav.Chat -> Conversation(
                client, top.thread, settings,
                replyTo = reply?.takeIf { it.first == top.thread }?.second,
                onReplyDone = { reply = null },
                editing = editing?.takeIf { it.first == top.thread }?.second,
                onEditDone = { editing = null },
                onBack = pop,
                onDetails = { push(Nav.Details(top.thread)) },
                onMessage = { push(Nav.Message(top.thread, it)) },
                onPhoto = { push(Nav.Photo(it)) },
            )
            is Nav.Photo -> PhotoScreen(top.path, onBack = pop)
            is Nav.Message -> MessagePage(
                client, top, settings, onBack = pop,
                onReply = { editing = null; reply = top.thread to top.message; pop() },
                onEdit = { reply = null; editing = top.thread to top.message; pop() },
            )
            is Nav.Details -> DetailsPage(
                client, top.thread, onBack = pop, onTimer = { push(Nav.Timer(top.thread)) }, onRename = { push(Nav.Rename(top.thread)) },
                onCleared = { stack.clear(); stack.add(Nav.Threads) },
                onMember = { id, name -> push(Nav.Member(top.thread, id, name)) },
                onSafetyNumber = { push(Nav.Safety(top.thread)) },
                onAddMember = { push(Nav.AddMember(top.thread)) },
            )
            is Nav.Member -> {
                val thread by remember(top.thread) { client.thread(top.thread) }.collectAsState(null)
                MemberScreen(
                    top.name, canRemove = thread?.canEdit == true, onBack = pop,
                    onMessage = { scope.launch { val chat = client.threadWith(top.member); stack.clear(); stack.addAll(listOf(Nav.Threads, Nav.Chat(chat))) } },
                    onRemove = { client.removeMember(top.thread, top.member); pop() },
                )
            }
            is Nav.AddMember -> {
                val contacts by remember { client.contacts() }.collectAsState(emptyList())
                ContactsScreen(contacts.map { ContactItem(it.id, it.name, it.number) }, onBack = pop, title = "Add member") { id ->
                    client.addMember(top.thread, id)
                    pop()
                }
            }
            is Nav.Safety -> {
                val thread by remember(top.thread) { client.thread(top.thread) }.collectAsState(null)
                val digits by produceState<String?>(null, top.thread) { value = client.safetyNumber(top.thread) }
                SafetyNumberScreen(thread?.title.orEmpty(), digits, onBack = pop)
            }
            Nav.Search -> {
                var results by remember { mutableStateOf(emptyList<SearchItem>()) }
                SearchScreen(
                    results, onBack = pop,
                    onQuery = { query -> scope.launch { results = client.search(query).map { SearchItem(it.messageId, it.threadId, it.title, it.body, shortTime(it.time)) } } },
                    onOpen = { push(Nav.Chat(it)) },
                )
            }
            is Nav.Timer -> {
                val thread by remember(top.thread) { client.thread(top.thread) }.collectAsState(null)
                TimerScreen(thread?.expireTimer ?: 0, ::duration, onBack = pop) { seconds ->
                    client.setTimer(top.thread, seconds)
                    pop()
                }
            }
            is Nav.Rename -> {
                val thread by remember(top.thread) { client.thread(top.thread) }.collectAsState(null)
                thread?.let { current ->
                    RenameScreen("Group name", current.title, onBack = pop) { name ->
                        client.renameGroup(top.thread, name)
                        pop()
                    }
                }
            }
            Nav.Contacts -> {
                val contacts by remember { client.contacts() }.collectAsState(emptyList())
                ContactsScreen(
                    contacts.map { ContactItem(it.id, it.name, it.number) },
                    onBack = pop,
                    onNewGroup = { pop(); push(Nav.NewGroup) },
                    onOpen = { id -> scope.launch { val thread = client.threadWith(id); pop(); push(Nav.Chat(thread)) } },
                )
            }
            is Nav.Settings -> SettingsPages(client, top.page, settings, onBack = pop, onOpen = { push(Nav.Settings(it)) }, onChecks = { push(Nav.Checks) }, onRelink = { push(Nav.Relink) }, onMakeMain = { push(Nav.MakeMain) })
            Nav.MakeMain -> {
                var busy by remember { mutableStateOf(false) }
                var error by remember { mutableStateOf<String?>(null) }
                ConfirmScreen(
                    "Main device", "Signal on this phone will be signed out. Still takes its place.", "Make Still the main device",
                    busy, error, onBack = pop,
                ) {
                    busy = true
                    scope.launch {
                        error = client.becomeMainDevice()
                        busy = false
                        if (error == null) restart()
                    }
                }
            }
            // Linking again is how history is fetched: Signal only offers the transfer to a device as it links.
            Nav.Relink -> Linking(client, onBack = { client.cancelLinking(); pop() }, onLinked = ::restart)
            Nav.Checks -> Checks(onBack = pop)
        }
    }

    private val lastThread = HashMap<Long, com.kongeskog.still.bridge.ThreadSummary>()
    private val lastMessages = HashMap<Long, List<com.kongeskog.still.bridge.ChatMessage>>()

    @Composable
    private fun Conversation(
        client: SignalClient, id: Long, settings: Settings, replyTo: Long?, onReplyDone: () -> Unit, editing: Long?, onEditDone: () -> Unit,
        onBack: () -> Unit, onDetails: () -> Unit, onMessage: (Long) -> Unit, onPhoto: (String) -> Unit,
    ) {
        // Start from what was last shown for this conversation: no blank frame on the way in,
        // on the way back from another screen, or when loading earlier messages.
        val thread by remember(id) { client.thread(id) }.collectAsState(lastThread[id])
        var limit by remember(id) { mutableStateOf(PAGE) }
        val messages by remember(id, limit) { client.messages(id, limit) }.collectAsState(lastMessages[id].orEmpty())
        thread?.let { lastThread[id] = it }
        if (messages.isNotEmpty()) lastMessages[id] = messages
        val typing by client.typing.collectAsState()
        val scope = rememberCoroutineScope()
        // Opening a conversation, or receiving into the open one, marks it read.
        LaunchedEffect(id, messages.size) { client.markRead(id) }
        val current = thread ?: return
        val label = when {
            id in typing -> "typing…"
            current.expireTimer > 0 -> "Signal · ${duration(current.expireTimer)}"
            else -> "Signal"
        }
        ConversationScreen(
            title = current.title,
            label = label,
            messages = remember(messages) {
                val items = ArrayList<BubbleItem>(messages.size + 8)
                var day = ""
                for (m in messages) {
                    // A line with the date wherever the day changes.
                    val date = dayLabel(m.sentAt)
                    if (date != day) {
                        day = date
                        items += BubbleItem(-m.id, false, null, date, "", system = true)
                    }
                    if (m.isEvent) {
                        items += BubbleItem(m.id, false, null, listOfNotNull(m.sender, m.body).joinToString(": ").takeIf { m.body.startsWith("Group") } ?: m.body, "", system = true)
                        continue
                    }
                    val failed = m.outgoing && m.status == MessageStatus.Failed
                    items += BubbleItem(
                        m.id, m.outgoing, m.sender, m.body,
                        footer = if (failed) "Not sent. Tap to retry" else (if (m.edited) "Edited · " else "") + timeOfDay(m.sentAt) + if (m.outgoing) mark(m.status) else "",
                        failed = failed, quote = m.quote, reactions = m.reactions,
                        attachments = m.attachments.map {
                            val waiting = if (it.path == null) " · tap to load" else ""
                            when (it.kind) {
                                AttachmentKind.Photo -> AttachmentItem(it.id, AttachmentType.Photo, it.path, "Photo$waiting")
                                AttachmentKind.Voice -> AttachmentItem(it.id, AttachmentType.Voice, it.path, "Voice note$waiting")
                                AttachmentKind.File -> AttachmentItem(it.id, AttachmentType.File, it.path, it.name)
                            }
                        },
                    )
                }
                items
            },
            editing = editing?.let { target -> messages.firstOrNull { it.id == target } }?.body,
            onCancelEdit = onEditDone,
            onLoadEarlier = if (messages.size >= limit) { { limit += PAGE } } else null,
            playing = player.playing.value,
            onAttachment = { attachmentId ->
                val file = messages.firstNotNullOfOrNull { m -> m.attachments.firstOrNull { it.id == attachmentId } }
                val path = file?.path
                when {
                    file == null -> Unit
                    path == null -> client.download(attachmentId)
                    file.kind == AttachmentKind.Photo -> onPhoto(path)
                    file.kind == AttachmentKind.Voice -> player.toggle(attachmentId, path)
                    else -> openFile(this, path, file.contentType)
                }
            },
            onAttach = {
                attachTo = id
                runCatching { picker.launch("*/*") }
            },
            recording = recorder.since.value?.let { RecordingUi(elapsed(it)) },
            onRecord = {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) recorder.start()
                else microphone.launch(Manifest.permission.RECORD_AUDIO)
            },
            onRecordCancel = recorder::cancel,
            onCall = if (!current.isGroup && !current.isSelf) { { withMicrophone { ConnectionService.start(this); client.startCall(id) } } } else null,
            onRecordSend = {
                recorder.finish()?.let { file -> scope.launch { client.sendFile(id, file.path, "audio/aac", null, voiceNote = true) } }
            },
            showSenders = current.isGroup,
            largeText = settings.largeText,
            enterSends = settings.enterSends,
            replyingTo = replyTo?.let { target -> messages.firstOrNull { it.id == target } }?.let { "${it.sender ?: "You"}: ${it.body.ifEmpty { "Attachment" }}" },
            onCancelReply = onReplyDone,
            onBack = onBack,
            onDetails = onDetails,
            onMessage = onMessage,
            onRetry = { messageId -> client.retry(messageId, id) },
            onTyping = { started -> client.typing(id, started) },
            onSend = { text ->
                if (editing != null) client.edit(editing, text) else scope.launch { client.send(id, text, replyTo) }
                onReplyDone()
                onEditDone()
            },
        )
    }

    /** Minutes and seconds since [start], re-read once a second while a recording runs. */
    @Composable
    private fun elapsed(start: Long): String {
        val now by produceState(System.currentTimeMillis(), start) {
            while (true) {
                delay(1000)
                value = System.currentTimeMillis()
            }
        }
        val seconds = ((now - start) / 1000).coerceAtLeast(0)
        return "%d:%02d".format(seconds / 60, seconds % 60)
    }

    /** Inside a conversation the date lines carry the day, so each message shows only its time. */
    private fun timeOfDay(time: Long): String = android.text.format.DateFormat.getTimeFormat(this).format(Date(time))

    private fun dayLabel(time: Long): String = when {
        DateUtils.isToday(time) -> "Today"
        DateUtils.isToday(time + DateUtils.DAY_IN_MILLIS) -> "Yesterday"
        else -> SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(time))
    }

    private fun mark(status: MessageStatus) = when (status) {
        MessageStatus.Sending -> " ·"
        MessageStatus.Sent -> " ✓"
        MessageStatus.Delivered, MessageStatus.Read -> " ✓✓"
        MessageStatus.Failed -> ""
    }

    @Composable
    private fun MessagePage(client: SignalClient, nav: Nav.Message, settings: Settings, onBack: () -> Unit, onReply: () -> Unit, onEdit: () -> Unit) {
        val thread by remember(nav.thread) { client.thread(nav.thread) }.collectAsState(null)
        val messages by remember(nav.thread) { client.messages(nav.thread) }.collectAsState(emptyList())
        val message = messages.firstOrNull { it.id == nav.message }
        // Deleted while open, or not loaded yet: nothing to act on.
        if (message == null) {
            if (messages.isNotEmpty()) LaunchedEffect(Unit) { onBack() }
            return
        }
        val status = when (message.status) {
            MessageStatus.Sending -> "Sending"
            MessageStatus.Sent -> "Sent"
            MessageStatus.Delivered -> "Delivered"
            MessageStatus.Read -> "Read"
            MessageStatus.Failed -> "Not sent"
        }
        MessageScreen(
            MessageActionsUi(
                body = message.body.ifEmpty { message.attachments.firstOrNull()?.name.orEmpty() }, sent = fullTime(message.sentAt), status = status.takeIf { message.outgoing },
                own = message.outgoing,
                // Signal accepts edits for a day after sending.
                canEdit = message.outgoing && message.body.isNotEmpty() && message.attachments.isEmpty() && message.status != MessageStatus.Failed &&
                    System.currentTimeMillis() - message.sentAt < DateUtils.DAY_IN_MILLIS,
            ),
            onBack = onBack,
            onReact = { emoji -> client.react(message.id, emoji); onBack() },
            onReply = onReply,
            onEdit = onEdit,
            onCopy = {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Message", message.body))
                onBack()
            },
            onDeleteForMe = { client.deleteForMe(message.id); onBack() },
            onDeleteForEveryone = { client.deleteForEveryone(message.id); onBack() },
        )
    }

    @Composable
    private fun DetailsPage(client: SignalClient, id: Long, onBack: () -> Unit, onTimer: () -> Unit, onRename: () -> Unit, onMember: (Long, String) -> Unit, onCleared: () -> Unit,
        onSafetyNumber: () -> Unit, onAddMember: () -> Unit,
    ) {
        val thread by remember(id) { client.thread(id) }.collectAsState(null)
        val settings by client.settings.collectAsState()
        var members by remember(id) { mutableStateOf(emptyList<ContactItem>()) }
        LaunchedEffect(id) { members = client.groupMembers(id).map { ContactItem(it.id, it.name, null) } }
        val current = thread ?: return
        DetailsScreen(
            DetailsUi(
                title = current.title,
                label = if (current.isGroup) "Group · ${members.size} members" else "Signal",
                isGroup = current.isGroup,
                timer = duration(current.expireTimer),
                canEdit = !current.isGroup || current.canEdit,
                muted = current.muted, pinned = current.pinned, blocked = current.blocked, archived = current.archived,
                canLeave = current.isGroup && members.isNotEmpty(),
                members = members,
            ),
            onBack = onBack, onTimer = onTimer, onRename = onRename,
            onMember = { memberId -> onMember(memberId, members.firstOrNull { it.id == memberId }?.name.orEmpty()) },
            onPin = { client.setPinned(id, it) },
            onBlock = { client.setBlocked(id, it) },
            onArchive = { client.setArchived(id, it) },
            onSafetyNumber = onSafetyNumber, onAddMember = onAddMember,
            onLeave = { client.leaveGroup(id); onBack() },
            onMute = { client.setMuted(id, it) },
            onDelete = { client.clearConversation(id); onCleared() },
        )
    }

    @Composable
    private fun SettingsPages(
        client: SignalClient, page: SettingsPage, settings: Settings,
        onBack: () -> Unit, onOpen: (SettingsPage) -> Unit, onChecks: () -> Unit, onRelink: () -> Unit, onMakeMain: () -> Unit,
    ) {
        val account by client.account.collectAsState()
        val connection by client.connection.collectAsState()
        val actions = remember(client) {
            object : SettingsActions {
                override fun readReceipts(on: Boolean) = client.update { it.copy(readReceipts = on) }
                override fun typingIndicators(on: Boolean) = client.update { it.copy(typingIndicators = on) }
                override fun notifications(on: Boolean) = client.update { it.copy(notifications = on) }
                override fun preview(choice: Int) = client.update { it.copy(preview = Preview.entries[choice]) }
                override fun enterSends(on: Boolean) = client.update { it.copy(enterSends = on) }
                override fun largeText(on: Boolean) = client.update { it.copy(largeText = on) }
                override fun checks() = onChecks()
                override fun transferHistory() = onRelink()
                override fun makeMain() = onMakeMain()
            }
        }
        SettingsScreen(
            page,
            SettingsUi(
                number = account?.number ?: "Unknown", device = if (account?.deviceId == 1) "Main device" else "Linked device ${account?.deviceId ?: 0}", connection = connection,
                isMain = account?.deviceId == 1,
                updateDue = client.updateDue, librariesUpdated = client.librariesUpdated,
                version = packageManager.getPackageInfo(packageName, 0).versionName ?: "",
                readReceipts = settings.readReceipts, typingIndicators = settings.typingIndicators,
                notifications = settings.notifications, preview = settings.preview.ordinal,
                enterSends = settings.enterSends, largeText = settings.largeText,
            ),
            actions, onBack, onOpen,
        )
    }

    private fun duration(seconds: Int): String = when {
        seconds == 0 -> "Off"
        seconds % 604800 == 0 -> "${seconds / 604800} wk"
        seconds % 86400 == 0 -> "${seconds / 86400} d"
        seconds % 3600 == 0 -> "${seconds / 3600} h"
        seconds % 60 == 0 -> "${seconds / 60} min"
        else -> "$seconds s"
    }

    private fun fullTime(time: Long): String =
        SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(time)) + ", " + android.text.format.DateFormat.getTimeFormat(this).format(Date(time))

    /**
     * Starts Still afresh. After linking again the running connection still belongs to the
     * old device; a new process is the simplest way to be sure nothing of it is left.
     */
    private fun restart() {
        val intent = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        getSystemService(AlarmManager::class.java).set(AlarmManager.RTC, System.currentTimeMillis() + 800, intent)
        finishAndRemoveTask()
        Runtime.getRuntime().exit(0)
    }

    /** Time of day for today, in the system's 12- or 24-hour style; otherwise day and month. */
    private fun shortTime(time: Long): String =
        if (DateUtils.isToday(time)) android.text.format.DateFormat.getTimeFormat(this).format(Date(time))
        else SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(time))

    @Composable
    private fun Linking(client: SignalClient, onBack: () -> Unit, onLinked: (() -> Unit)? = null) {
        // Linking belongs to the client, not this screen, so Back does not cancel it.
        LaunchedEffect(Unit) { client.startLinking() }
        val link by client.linkState.collectAsState()
        if (onLinked != null && link == LinkState.Linked) LaunchedEffect(Unit) { onLinked() }
        val state = when (val it = link) {
            null, LinkState.Connecting -> LinkUi.Connecting
            is LinkState.ShowCode -> LinkUi.Code(it.url)
            LinkState.Registering, LinkState.Linked -> LinkUi.Registering // the account flow switches screens
            is LinkState.Failed -> LinkUi.Failed(it.reason)
        }
        BackHandler(onBack = onBack)
        LinkScreen(state, onBack, onRetry = { client.cancelLinking(); client.startLinking() })
    }

    @Composable
    private fun Checks(onBack: () -> Unit) {
        val lines = remember { mutableStateListOf<CheckLine>() }
        var running by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        BackHandler(onBack = onBack)
        SpikeScreen(lines, running, onBack) {
            lines.clear()
            running = true
            scope.launch {
                withContext(Dispatchers.IO) {
                    Spike.run { step -> runOnUiThread { lines += CheckLine(step.name, step.ok, step.detail) } }
                }
                running = false
            }
        }
    }
}
