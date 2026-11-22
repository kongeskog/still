package com.kongeskog.still

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.mudita.mmd.ThemeMMD
import com.kongeskog.still.ui.AttachmentItem
import com.kongeskog.still.ui.AttachmentType
import com.kongeskog.still.ui.BubbleItem
import com.kongeskog.still.ui.CallScreen
import com.kongeskog.still.ui.CallUi
import com.kongeskog.still.ui.NewGroupScreen
import com.kongeskog.still.ui.SafetyNumberScreen
import com.kongeskog.still.ui.SearchItem
import com.kongeskog.still.ui.SearchScreen
import com.kongeskog.still.ui.RecordingUi
import com.kongeskog.still.ui.ContactItem
import com.kongeskog.still.ui.ContactsScreen
import com.kongeskog.still.ui.ConversationListScreen
import com.kongeskog.still.ui.ConversationScreen
import com.kongeskog.still.ui.DetailsScreen
import com.kongeskog.still.ui.DetailsUi
import com.kongeskog.still.ui.MessageActionsUi
import com.kongeskog.still.ui.MessageScreen
import com.kongeskog.still.ui.SettingsActions
import com.kongeskog.still.ui.SettingsPage
import com.kongeskog.still.ui.SettingsScreen
import com.kongeskog.still.ui.SettingsUi
import com.kongeskog.still.ui.ThreadItem
import com.kongeskog.still.ui.TimerScreen

/**
 * Every screen with made-up data, so layout can be checked without an account:
 * `adb shell am start -n com.kongeskog.still.debug/com.kongeskog.still.DemoActivity --es screen chat`.
 */
class DemoActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val screen = intent.getStringExtra("screen") ?: "list"
        val people = listOf("Ada Lovelace", "Grace Hopper", "Katherine Johnson", "Margaret Hamilton").mapIndexed { i, n -> ContactItem(i.toLong(), n, "+1 555 010$i") }
        val settings = SettingsUi(number = "+1 555 0100", device = "Main device", connection = "Connected", version = "0.1.0", isMain = true, updateDue = false, librariesUpdated = "2026-11-21", readReceipts = true, typingIndicators = true, notifications = true, preview = 0, enterSends = false, largeText = false)
        val none = object : SettingsActions {
            override fun readReceipts(on: Boolean) = Unit
            override fun typingIndicators(on: Boolean) = Unit
            override fun notifications(on: Boolean) = Unit
            override fun preview(choice: Int) = Unit
            override fun enterSends(on: Boolean) = Unit
            override fun largeText(on: Boolean) = Unit
            override fun checks() = Unit
            override fun transferHistory() = Unit
            override fun makeMain() = Unit
        }
        val bubbles = listOf(
            BubbleItem(-1, false, null, "Today", "", system = true),
            BubbleItem(8, false, null, "Missed voice call", "", system = true),
            BubbleItem(1, false, "Ada Lovelace", "Are we still on for the walk tomorrow?", "9:12 AM"),
            BubbleItem(2, true, null, "Yes. Ten at the gate?", "9:14 AM ✓✓", reactions = "👍"),
            BubbleItem(3, false, "Grace Hopper", "I'll bring coffee", "9:20 AM", quote = "You: Yes. Ten at the gate?"),
            BubbleItem(5, true, null, "Perfect", "Edited · 9:31 AM ✓"),
            BubbleItem(6, false, "Ada Lovelace", "", "9:40 AM", attachments = listOf(AttachmentItem(1, AttachmentType.Voice, "x", "Voice note"))),
            BubbleItem(7, false, "Ada Lovelace", "The route", "9:41 AM", attachments = listOf(AttachmentItem(2, AttachmentType.File, null, "route.pdf"))),
        )
        setContent {
            ThemeMMD {
                when (screen) {
                    "list" -> ConversationListScreen(
                        listOf(
                            ThreadItem(1, "Ada Lovelace", "Are we still on for the walk tomorrow?", "9:12 AM", 2),
                            ThreadItem(2, "Book club", "Grace: I'll bring coffee", "8:40 AM", 0),
                            ThreadItem(3, "Note to Self", "Milk, eggs, stamps", "Thursday", 0),
                            ThreadItem(4, "Family", "", "", 0),
                        ),
                        status = null, onOpen = {}, onNew = {}, onSettings = {},
                    )
                    "chat", "chat-reply", "chat-rec" -> ConversationScreen(
                        "Book club", "Signal · 1 wk", bubbles, showSenders = true, largeText = false, enterSends = false,
                        replyingTo = "Grace Hopper: I'll bring coffee".takeIf { screen == "chat-reply" }, onCancelReply = {},
                        onBack = {}, onDetails = {}, onMessage = {}, onRetry = {}, onTyping = {},
                        recording = RecordingUi("0:07").takeIf { screen == "chat-rec" },
                        onSend = { _ -> },
                    )
                    "conversation" -> ConversationScreen(
                        "Ada Lovelace", "Signal · 1 wk",
                        listOf(
                            BubbleItem(-1, false, null, "Today", "", system = true),
                            BubbleItem(1, false, null, "Are we still on for the walk tomorrow?", "9:12 AM"),
                            BubbleItem(2, true, null, "Yes. Ten at the gate?", "9:14 AM ✓✓", reactions = "👍"),
                            BubbleItem(3, false, null, "I'll bring coffee", "9:20 AM", quote = "You: Yes. Ten at the gate?"),
                            BubbleItem(4, true, null, "Perfect", "Edited · 9:31 AM ✓"),
                        ),
                        showSenders = false, largeText = false, enterSends = false, replyingTo = null, onCancelReply = {},
                        onBack = {}, onDetails = {}, onMessage = {}, onRetry = {}, onTyping = {}, onCall = {}, onSend = { _ -> },
                    )
                    "message" -> MessageScreen(MessageActionsUi("Yes. Ten at the gate?", "21 Nov, 9:14 AM", "Read", own = true, canEdit = true), {}, {}, {}, {}, {}, {}, {})
                    "details" -> DetailsScreen(DetailsUi(title = "Book club", label = "Group · 4 members", isGroup = true, timer = "1 wk", canEdit = true, muted = false, pinned = true, canLeave = true, members = people), {}, {}, {}, {}, {}, {})
                    "timer" -> TimerScreen(604800, { if (it == 0) "Off" else "$it s" }, {}, {})
                    "contacts" -> ContactsScreen(people, {}, onNewGroup = {}) {}
                    "captcha" -> CaptchaScreen({}) {}
                    "newgroup" -> NewGroupScreen(people, false, {}) { _, _ -> }
                    "call" -> CallScreen(CallUi("Ada Lovelace", "2:41", incoming = false, muted = false, speaker = true), {}, {}, {}, {})
                    "call-in" -> CallScreen(CallUi("Ada Lovelace", "Incoming call…", incoming = true, muted = false, speaker = false), {}, {}, {}, {})
                    "safety" -> SafetyNumberScreen("Ada Lovelace", "123456789012345678901234567890123456789012345678901234567890".chunked(5).mapIndexed { i, _ -> "%05d".format((i * 7919 + 10007) % 100000) }.joinToString(""), {})
                    "search" -> SearchScreen(
                        listOf(SearchItem(1, 1, "Ada Lovelace", "Are we still on for the walk tomorrow?", "9:12 AM"), SearchItem(2, 2, "Book club", "Walk first, then coffee", "Thursday")),
                        {}, {}, {},
                    )
                    else -> SettingsScreen(SettingsPage.entries.firstOrNull { it.name.equals(screen, true) } ?: SettingsPage.Main, settings, none, {}, {})
                }
            }
        }
    }
}
