package com.kongeskog.still.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldDefaultsMMD
import com.mudita.mmd.components.text_field.TextFieldMMD
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class ThreadItem(val id: Long, val title: String, val snippet: String, val time: String, val unread: Int)

/** The conversation list, laid out like the list in Messages. */
@Composable
fun ConversationListScreen(
    threads: List<ThreadItem>, status: String?, onOpen: (Long) -> Unit, onNew: () -> Unit, onSettings: () -> Unit, onSearch: () -> Unit = {},
    /** Number of archived conversations; a row at the end of the list opens them. */
    archived: Int = 0, onArchived: () -> Unit = {},
    /** Set when this screen is itself the archive. */
    onBack: (() -> Unit)? = null,
) {
    Screen(if (onBack != null) "Archived" else "Still", onBack, label = status, actions = {
        if (onBack == null) {
            BarAction(Icons.Search, "Search", onSearch)
            BarAction(Icons.Settings, "Settings", onSettings)
            BarPrimary(Icons.Plus, "New message", onNew)
        }
    }) {
        if (threads.isEmpty() && archived == 0) {
            // A new account's first screen: say why it is empty and where to start.
            TextMMD("No conversations yet. Tap + to write to someone.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
        }
        LazyColumnMMD(Modifier.fillMaxSize(), scrollStep = 0, isScrollbarVisible = false) {
            items(threads.size, key = { threads[it].id }) { i ->
                val thread = threads[i]
                val unread = thread.unread > 0
                Column(
                    Modifier.fillMaxWidth().height(72.dp).clickable { onOpen(thread.id) }.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextMMD(
                            thread.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.width(8.dp))
                        // Unread is a solid dot and a bold preview, never a colour.
                        if (unread) Box(Modifier.padding(end = 6.dp).size(10.dp).background(Color.Black, CircleShape))
                        TextMMD(thread.time, style = MaterialTheme.typography.bodySmall)
                    }
                    TextMMD(
                        thread.snippet, style = MaterialTheme.typography.bodyMedium, fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                DottedDivider()
            }
            if (archived > 0) item(key = "archived") { NavRow("Archived", "$archived", onArchived) }
        }
    }
}

data class BubbleItem(
    val id: Long, val outgoing: Boolean, val sender: String?, val body: String, val footer: String,
    /** Set on a message that was not sent; a tap then tries again. */
    val failed: Boolean = false,
    /** "Author: text" of the message replied to. */
    val quote: String? = null,
    val reactions: String? = null,
    val attachments: List<AttachmentItem> = emptyList(),
    /** A centred line with no bubble: a date, a missed call, a group change. */
    val system: Boolean = false,
)

enum class AttachmentType { Photo, Voice, File }

/** A file on a message. [path] is null until it is on this phone. */
data class AttachmentItem(val id: Long, val type: AttachmentType, val path: String?, val label: String)

/** The voice note being recorded: its length so far, such as "0:05". */
data class RecordingUi(val elapsed: String)

/** A conversation in the style of Mudita's Messages app. [onSend] is null where writing is not allowed. */
@Composable
fun ConversationScreen(
    title: String,
    label: String?,
    messages: List<BubbleItem>,
    showSenders: Boolean,
    largeText: Boolean,
    enterSends: Boolean,
    /** "Author: text" of the message being replied to, shown above the composer. */
    replyingTo: String?,
    onCancelReply: () -> Unit,
    /** The text of the sent message being edited; it fills the composer. */
    editing: String? = null,
    onCancelEdit: () -> Unit = {},
    /** Set while older messages exist that are not shown. */
    onLoadEarlier: (() -> Unit)? = null,
    onBack: () -> Unit,
    onDetails: () -> Unit,
    onMessage: (Long) -> Unit,
    onRetry: (Long) -> Unit,
    onTyping: (Boolean) -> Unit,
    /** The voice note that is playing, if any. */
    playing: Long? = null,
    onAttachment: (Long) -> Unit = {},
    onAttach: () -> Unit = {},
    recording: RecordingUi? = null,
    onRecord: () -> Unit = {},
    onRecordCancel: () -> Unit = {},
    onRecordSend: () -> Unit = {},
    /** Set where a voice call can be placed: a conversation with one other person. */
    onCall: (() -> Unit)? = null,
    onSend: ((String) -> Unit)?,
) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var height by remember { mutableIntStateOf(0) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    // A tap on the messages closes the keyboard; a drag still scrolls.
    val tapToDismiss = Modifier.pointerInput(Unit) {
        detectTapGestures {
            keyboard?.hide()
            focus.clearFocus()
        }
    }
    LaunchedEffect(messages.lastOrNull()?.id) {
        if (messages.isNotEmpty()) list.scrollToItem(messages.lastIndex, Int.MAX_VALUE / 2)
    }

    Screen(title, onBack, label, onTitle = onDetails, actions = {
        if (onCall != null) BarAction(Icons.Phone, "Call", onCall)
        BarAction(Icons.More, "Details", onDetails)
    }) {
        if (messages.isEmpty()) {
            Spacer(Modifier.weight(1f).fillMaxWidth().then(tapToDismiss))
        } else {
            // scrollStep = 0 makes the list follow the finger, as in Messages.
            LazyColumnMMD(
                modifier = Modifier.weight(1f).fillMaxWidth().then(tapToDismiss).onSizeChanged { size ->
                    // The keyboard takes height from the bottom; keep the newest text in view.
                    val lost = height - size.height
                    height = size.height
                    if (lost > 0) scope.launch { list.scrollBy(lost.toFloat()) }
                },
                state = list,
                contentPadding = PaddingValues(12.dp, 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.Bottom),
                scrollStep = 0,
                isScrollbarVisible = false,
            ) {
                if (onLoadEarlier != null) {
                    item(key = "earlier") {
                        Box(Modifier.fillMaxWidth().height(48.dp).clickable(onClick = onLoadEarlier), contentAlignment = Alignment.Center) {
                            TextMMD("Load earlier", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                items(messages.size, key = { messages[it].id }) { Bubble(messages[it], showSenders, largeText, playing, onMessage, onRetry, onAttachment) }
            }
        }
        HorizontalDividerMMD()
        if (onSend == null) {
            Box(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                TextMMD("Sending is off in Settings", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            val banner = if (editing != null) "Editing" else replyingTo
            if (banner != null) {
                Row(Modifier.fillMaxWidth().height(48.dp).padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(2.dp).height(28.dp).background(Color.Black))
                    TextMMD(banner, Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    BarAction(Icons.Close, "Cancel", if (editing != null) onCancelEdit else onCancelReply)
                }
            }
            if (recording != null) {
                // Recording takes the composer's place; only the digits change while it runs.
                Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextMMD("Recording ${recording.elapsed}", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    SquareButton(Icons.Close, "Discard", onRecordCancel)
                    SquareButton(Icons.Send, "Send voice note", onRecordSend)
                }
            } else {
                Composer(title, editing, enterSends, onTyping, onAttach, onRecord, onSend)
            }
        }
        HorizontalDividerMMD()
    }
}

@Composable
private fun Bubble(
    item: BubbleItem, showSender: Boolean, largeText: Boolean, playing: Long?,
    onMessage: (Long) -> Unit, onRetry: (Long) -> Unit, onAttachment: (Long) -> Unit,
) {
    val style = if (largeText) MaterialTheme.typography.titleLarge.copy(lineHeight = 30.sp, fontWeight = FontWeight.Normal)
    else MaterialTheme.typography.bodyLarge.copy(lineHeight = 25.sp, letterSpacing = 0.2.sp)
    if (item.system) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            TextMMD(item.body, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
        }
        return
    }
    val shape = if (item.outgoing) RoundedCornerShape(12.dp, 12.dp, 0.dp, 12.dp) else RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().padding(start = if (item.outgoing) 30.dp else 0.dp, end = if (item.outgoing) 0.dp else 30.dp),
        horizontalAlignment = if (item.outgoing) Alignment.End else Alignment.Start,
    ) {
        if (showSender && !item.outgoing && item.sender != null) {
            TextMMD(item.sender, Modifier.padding(start = 4.dp, bottom = 4.dp), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
        }
        // Direction is carried by border weight and corner, never by fill.
        Column(Modifier.border(if (item.outgoing) 2.dp else 1.dp, Color.Black, shape).clickable { onMessage(item.id) }.padding(10.dp, 8.dp)) {
            if (item.quote != null) {
                Row(Modifier.padding(bottom = 6.dp).height(IntrinsicSize.Min)) {
                    Box(Modifier.width(2.dp).fillMaxHeight().background(Color.Black))
                    TextMMD(item.quote, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            item.attachments.forEach { file ->
                if (file.type == AttachmentType.Photo && file.path != null) {
                    BubbleImage(file.path) { onAttachment(file.id) }
                } else {
                    Row(Modifier.heightIn(min = 48.dp).clickable { onAttachment(file.id) }, verticalAlignment = Alignment.CenterVertically) {
                        val icon = when (file.type) {
                            AttachmentType.Voice -> if (playing == file.id) Icons.Stop else Icons.Play
                            AttachmentType.Photo -> Icons.Attach
                            AttachmentType.File -> Icons.File
                        }
                        Icon(icon, null, Modifier.size(28.dp))
                        TextMMD(file.label, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (item.body.isNotEmpty()) TextMMD(item.body, style = style)
        }
        if (item.reactions != null) {
            Spacer(Modifier.height(4.dp))
            Box(Modifier.border(1.dp, Color.Black, RoundedCornerShape(12.dp)).padding(8.dp, 2.dp)) {
                TextMMD(item.reactions, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(4.dp))
        if (item.failed) {
            Box(Modifier.height(48.dp).clickable { onRetry(item.id) }.padding(horizontal = 4.dp), contentAlignment = Alignment.CenterStart) {
                TextMMD(item.footer, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            }
        } else {
            TextMMD(item.footer, Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Composer(
    conversation: String, editing: String?, enterSends: Boolean, onTyping: (Boolean) -> Unit,
    onAttach: () -> Unit, onRecord: () -> Unit, onSend: (String) -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    var draft by remember(conversation) { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    // A newly opened conversation shows a blinking cursor like Messages. It is drawn, not real
    // focus, because real focus always raises the keyboard. Gone after a send.
    var justOpened by remember(conversation) { mutableStateOf(true) }
    var typing by remember(conversation) { mutableStateOf(false) }
    // Starting an edit puts the old text in the field; leaving it empties the field again.
    LaunchedEffect(editing) { draft = editing.orEmpty() }
    val send = {
        if (draft.isNotBlank()) {
            onSend(draft.trim())
            draft = ""
            typing = false
            justOpened = false
        }
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = 4.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        BarAction(Icons.Attach, "Attach", onAttach)
        // Pulled left: the field's own 16dp inset on top of the button's margin left the cursor adrift.
        Box(Modifier.weight(1f).offset(x = (-10).dp), contentAlignment = Alignment.CenterStart) {
            TextFieldMMD(
                value = draft,
                onValueChange = {
                    draft = it
                    // One "started" per burst of typing, one "stopped" when the field empties.
                    if (it.isNotEmpty() != typing) {
                        typing = it.isNotEmpty()
                        onTyping(typing)
                    }
                },
                modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
                textStyle = MaterialTheme.typography.bodyLarge,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = if (enterSends) ImeAction.Send else ImeAction.Default,
                ),
                keyboardActions = KeyboardActions(onSend = { send() }),
                maxLines = 4,
                // Borderless, like Messages: the rules above and below already bound the composer.
                colors = TextFieldDefaultsMMD.colors(focusedIndicatorColor = Color.White, unfocusedIndicatorColor = Color.White),
            )
            if (justOpened && !focused && draft.isEmpty()) DrawnCursor()
        }
        // An empty field offers a voice note instead, as Signal does.
        if (draft.isEmpty()) SquareButton(Icons.Mic, "Record voice note", onRecord) else SquareButton(Icons.Send, "Send", send)
    }
}

/** A photo inside a bubble, decoded small off the main thread. */
@Composable
private fun BubbleImage(path: String, onClick: () -> Unit) {
    val image by produceState<ImageBitmap?>(null, path) { value = withContext(Dispatchers.IO) { decode(path, 480) } }
    val bitmap = image
    if (bitmap == null) {
        Box(Modifier.size(200.dp, 120.dp))
    } else {
        Image(
            bitmap, "Photo",
            Modifier.padding(bottom = 4.dp).widthIn(max = 260.dp).heightIn(max = 320.dp).clickable(onClick = onClick),
            contentScale = ContentScale.Fit,
        )
    }
}

/** Decodes a picture no larger than about [maxSide] pixels on its longer side. */
fun decode(path: String, maxSide: Int): ImageBitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
} catch (e: Exception) {
    null
}

/** One photo, as large as the screen allows. */
@Composable
fun PhotoScreen(path: String, onBack: () -> Unit) {
    val image by produceState<ImageBitmap?>(null, path) { value = withContext(Dispatchers.IO) { decode(path, 1200) } }
    Screen("Photo", onBack) {
        image?.let { Image(it, "Photo", Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
    }
}

/** Matches the text field's own cursor. Takes no touches, so a tap reaches the field below. */
@Composable
private fun DrawnCursor() {
    var on by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            on = !on
        }
    }
    if (on) Box(Modifier.padding(start = 16.dp).size(2.dp, 25.dp).background(Color.Black))
}

data class MessageActionsUi(
    val body: String, val sent: String, val status: String?, val own: Boolean,
    val canWrite: Boolean = true,
    /** Our own text, recent enough that Signal still lets it be changed. */
    val canEdit: Boolean = false,
)

/** What can be done with one message. A full screen, not a pop-up: overlays ghost on this display. */
@Composable
fun MessageScreen(
    state: MessageActionsUi,
    onBack: () -> Unit,
    onReact: (String) -> Unit,
    onReply: () -> Unit,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onDeleteForMe: () -> Unit,
    onDeleteForEveryone: () -> Unit,
) {
    Screen("Message", onBack, label = listOfNotNull(state.sent, state.status).joinToString(" · ")) {
        TextMMD(state.body, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge, maxLines = 4, overflow = TextOverflow.Ellipsis)
        HorizontalDividerMMD(thickness = 1.dp)
        if (state.canWrite) {
            Row(Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf("👍", "❤️", "😂", "😮", "😢", "🙏").forEach { emoji ->
                    Box(Modifier.weight(1f).fillMaxHeight().clickable { onReact(emoji) }, contentAlignment = Alignment.Center) {
                        TextMMD(emoji, style = MaterialTheme.typography.headlineLarge)
                    }
                }
            }
            DottedDivider()
            ListRow("Reply", onClick = onReply) { Icon(Icons.Reply, null, Modifier.size(24.dp)) }
        }
        if (state.canEdit) ListRow("Edit", onClick = onEdit)
        ListRow("Copy", onClick = onCopy)
        ListRow("Delete for me", onClick = onDeleteForMe)
        if (state.own && state.canWrite) ListRow("Delete for everyone", onClick = onDeleteForEveryone)
    }
}


data class CallUi(val name: String, val status: String, val incoming: Boolean, val muted: Boolean, val speaker: Boolean)

/**
 * A voice call, laid out like the Kompakt's own phone call screens: status and name at the
 * top, round buttons with a label under each, and the red-button equivalent (an outlined
 * circle with a lowered handset) at the bottom. Only the timer changes while a call runs.
 */
@Composable
fun CallScreen(state: CallUi, onAnswer: () -> Unit, onEnd: () -> Unit, onMute: (Boolean) -> Unit, onSpeaker: (Boolean) -> Unit) {
    Column(Modifier.fillMaxSize().background(Color.White), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(72.dp))
        TextMMD(state.status, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        TextMMD(
            state.name, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        TextMMD("Signal", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.weight(1f))
        if (state.incoming) {
            Row(Modifier.padding(bottom = 40.dp), verticalAlignment = Alignment.Top) {
                CallButton(Icons.Phone, "Decline", filled = false, lowered = true, onClick = onEnd)
                Spacer(Modifier.width(104.dp))
                CallButton(Icons.Phone, "Accept", filled = true, onClick = onAnswer)
            }
        } else {
            Row(Modifier.padding(bottom = 40.dp), verticalAlignment = Alignment.Top) {
                CallButton(Icons.MicOff, "Mute", filled = state.muted, outlined = false) { onMute(!state.muted) }
                Spacer(Modifier.width(72.dp))
                CallButton(Icons.Speaker, "Speaker", filled = state.speaker, outlined = false) { onSpeaker(!state.speaker) }
            }
            Box(Modifier.padding(bottom = 32.dp)) { CallButton(Icons.Phone, "End call", filled = false, lowered = true, onClick = onEnd) }
        }
    }
}

/**
 * A round call button with its label underneath. Filled black means "on" or "accept";
 * an outline with a lowered handset means hanging up; a bare icon is a switch that is off.
 */
@Composable
private fun CallButton(icon: ImageVector, label: String, filled: Boolean, outlined: Boolean = true, lowered: Boolean = false, onClick: () -> Unit) {
    Column(Modifier.width(96.dp).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(72.dp)
                .then(if (filled) Modifier.background(Color.Black, CircleShape) else if (outlined) Modifier.border(2.dp, Color.Black, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(36.dp).then(if (lowered) Modifier.rotate(135f) else Modifier), tint = if (filled) Color.White else Color.Black)
        }
        TextMMD(label, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
    }
}
