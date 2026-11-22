package com.kongeskog.still.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldMMD
import androidx.compose.ui.Modifier
import com.mudita.mmd.components.lazy.LazyColumnMMD

data class ContactItem(val id: Long, val name: String, val number: String?)

/** People to start a conversation with, narrowed as a name is typed. */
@Composable
fun ContactsScreen(
    contacts: List<ContactItem>, onBack: () -> Unit, title: String = "New message",
    /** Set where starting a group is offered. */
    onNewGroup: (() -> Unit)? = null,
    onOpen: (Long) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val shown = if (query.isBlank()) contacts else contacts.filter { it.name.contains(query.trim(), ignoreCase = true) }
    Screen(title, onBack) {
        TextFieldMMD(
            value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            textStyle = MaterialTheme.typography.bodyLarge, singleLine = true,
            placeholder = { TextMMD("Search", style = MaterialTheme.typography.bodyLarge) },
        )
        LazyColumnMMD(Modifier.fillMaxSize(), scrollStep = 0, isScrollbarVisible = false) {
            if (onNewGroup != null && query.isBlank()) item(key = "group") { NavRow("New group", onClick = onNewGroup) }
            items(shown.size, key = { shown[it].id }) { i ->
                val contact = shown[i]
                ListRow(contact.name, contact.number, onClick = { onOpen(contact.id) })
            }
        }
    }
}

/** Names a new group and picks who is in it. */
@Composable
fun NewGroupScreen(contacts: List<ContactItem>, busy: Boolean, onBack: () -> Unit, onCreate: (String, List<Long>) -> Unit) {
    var name by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(emptySet<Long>()) }
    Screen("New group", onBack) {
        TextFieldMMD(
            value = name, onValueChange = { name = it.take(32) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            textStyle = MaterialTheme.typography.bodyLarge, singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            placeholder = { TextMMD("Group name", style = MaterialTheme.typography.bodyLarge) },
        )
        LazyColumnMMD(Modifier.weight(1f).fillMaxWidth(), scrollStep = 0, isScrollbarVisible = false) {
            items(contacts.size, key = { contacts[it].id }) { i ->
                val contact = contacts[i]
                ChoiceRow(contact.name, contact.id in picked) { picked = if (contact.id in picked) picked - contact.id else picked + contact.id }
            }
        }
        PrimaryButton(
            if (busy) "Creating…" else "Create", Modifier.fillMaxWidth().padding(16.dp, 8.dp),
            enabled = !busy && name.isNotBlank() && picked.isNotEmpty(),
        ) { onCreate(name.trim(), picked.toList()) }
    }
}

data class DetailsUi(
    val title: String, val label: String?, val isGroup: Boolean, val timer: String,
    /** False in a group whose settings only its admins may change, when this account is not one. */
    val canEdit: Boolean,
    val muted: Boolean,
    val pinned: Boolean = false,
    val blocked: Boolean = false,
    val archived: Boolean = false,
    /** A group we are still in. */
    val canLeave: Boolean = false,
    val members: List<ContactItem>,
)

/** A conversation's details: its timer and, for a group, who is in it. */
@Composable
fun DetailsScreen(
    state: DetailsUi, onBack: () -> Unit, onTimer: () -> Unit, onRename: () -> Unit, onMute: (Boolean) -> Unit,
    onDelete: () -> Unit, onMember: (Long) -> Unit,
    onPin: (Boolean) -> Unit = {}, onBlock: (Boolean) -> Unit = {}, onSafetyNumber: () -> Unit = {},
    onAddMember: () -> Unit = {}, onLeave: () -> Unit = {}, onArchive: (Boolean) -> Unit = {},
) {
    // Deleting and leaving ask once more in the same row, so no other line moves.
    var confirming by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    Screen(state.title, onBack, state.label) {
        LazyColumnMMD(Modifier.fillMaxSize(), scrollStep = 0, isScrollbarVisible = false) {
            item {
                if (state.isGroup && state.canEdit) NavRow("Group name", state.title, onRename)
                if (state.canEdit) NavRow("Disappearing messages", state.timer, onTimer)
                else ListRow("Disappearing messages", state.timer)
                SwitchRow("Mute", state.muted, onMute)
                SwitchRow("Pin", state.pinned, onPin)
                SwitchRow("Archive", state.archived, onArchive)
                SwitchRow("Block", state.blocked, onBlock)
                if (!state.isGroup) NavRow("Safety number", onClick = onSafetyNumber)
                if (state.isGroup && state.canEdit) NavRow("Add member", onClick = onAddMember)
                if (state.canLeave) ListRow(if (leaving) "Tap again to leave" else "Leave group", onClick = { if (leaving) onLeave() else leaving = true })
                ListRow(if (confirming) "Tap again to delete" else "Delete conversation", onClick = { if (confirming) onDelete() else confirming = true })
            }
            items(state.members.size, key = { state.members[it].id }) { i ->
                val member = state.members[i]
                ListRow(member.name, onClick = { onMember(member.id) })
            }
        }
    }
}

/** One member of a group: write to them, or take them out. */
@Composable
fun MemberScreen(name: String, canRemove: Boolean, onBack: () -> Unit, onMessage: () -> Unit, onRemove: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    Screen(name, onBack) {
        NavRow("Message", onClick = onMessage)
        if (canRemove) ListRow(if (confirming) "Tap again to remove" else "Remove from group", onClick = { if (confirming) onRemove() else confirming = true })
    }
}

/** The sixty digits both people can compare, set in the same four rows of three as Signal shows them. */
@Composable
fun SafetyNumberScreen(name: String, digits: String?, onBack: () -> Unit) {
    Screen("Safety number", onBack, label = name) {
        if (digits == null) {
            TextMMD("No messages exchanged yet", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
        } else {
            Column(Modifier.fillMaxWidth().padding(top = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                digits.chunked(5).chunked(3).forEach { row ->
                    TextMMD(row.joinToString("  "), Modifier.padding(vertical = 10.dp), style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace))
                }
            }
        }
    }
}

data class SearchItem(val messageId: Long, val threadId: Long, val title: String, val body: String, val time: String)

@Composable
fun SearchScreen(results: List<SearchItem>, onBack: () -> Unit, onQuery: (String) -> Unit, onOpen: (Long) -> Unit) {
    var query by remember { mutableStateOf("") }
    Screen("Search", onBack) {
        TextFieldMMD(
            value = query, onValueChange = { query = it; onQuery(it) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            textStyle = MaterialTheme.typography.bodyLarge, singleLine = true,
            placeholder = { TextMMD("Search messages", style = MaterialTheme.typography.bodyLarge) },
        )
        LazyColumnMMD(Modifier.fillMaxSize(), scrollStep = 0, isScrollbarVisible = false) {
            items(results.size, key = { results[it].messageId }) { i ->
                val hit = results[i]
                ListRow(hit.title, hit.body, onClick = { onOpen(hit.threadId) }) { TextMMD(hit.time, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

/** Seconds of each offered timer, as in Signal. */
val TIMER_CHOICES = listOf(0, 30, 300, 3600, 28800, 86400, 604800, 2419200)

@Composable
fun TimerScreen(current: Int, format: (Int) -> String, onBack: () -> Unit, onPick: (Int) -> Unit) {
    Screen("Disappearing messages", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            (TIMER_CHOICES + current).distinct().sorted().forEach { seconds ->
                ChoiceRow(format(seconds), seconds == current) { onPick(seconds) }
            }
        }
    }
}

/** One line of text to change, such as a group's name. */
@Composable
fun RenameScreen(title: String, current: String, onBack: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    Screen(title, onBack) {
        TextFieldMMD(
            value = text, onValueChange = { text = it.take(32) }, modifier = Modifier.fillMaxWidth().padding(16.dp),
            textStyle = MaterialTheme.typography.bodyLarge, singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        )
        PrimaryButton("Save", Modifier.fillMaxWidth().padding(horizontal = 16.dp), enabled = text.isNotBlank() && text.trim() != current) { onSave(text.trim()) }
    }
}

/** One step that cannot be taken back: a single line saying what it does, and the button. */
@Composable
fun ConfirmScreen(title: String, line: String, action: String, busy: Boolean, error: String?, onBack: () -> Unit, onConfirm: () -> Unit) {
    Screen(title, onBack) {
        TextMMD(error ?: line, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
        PrimaryButton(if (busy) "Working…" else action, Modifier.fillMaxWidth().padding(horizontal = 16.dp), enabled = !busy, onClick = onConfirm)
    }
}

enum class SettingsPage { Main, Account, Privacy, Notifications, Chats, About }

data class SettingsUi(
    val number: String, val device: String, val connection: String, val version: String,
    /** True once Still is the account's main device rather than a linked one. */
    val isMain: Boolean = false,
    val updateDue: Boolean = false,
    val librariesUpdated: String = "",
    val readReceipts: Boolean, val typingIndicators: Boolean,
    val notifications: Boolean, val preview: Int,
    val enterSends: Boolean, val largeText: Boolean,
)

interface SettingsActions {
    fun readReceipts(on: Boolean)
    fun typingIndicators(on: Boolean)
    fun notifications(on: Boolean)
    fun preview(choice: Int)
    fun enterSends(on: Boolean)
    fun largeText(on: Boolean)
    fun checks()
    fun transferHistory()
    fun makeMain()
}

/** Settings as plain rows, one topic per screen. Labels only: a row says what it is and nothing more. */
@Composable
fun SettingsScreen(page: SettingsPage, state: SettingsUi, actions: SettingsActions, onBack: () -> Unit, onOpen: (SettingsPage) -> Unit) {
    val title = if (page == SettingsPage.Main) "Settings" else page.name
    Screen(title, onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            when (page) {
                SettingsPage.Main -> {
                    NavRow("Account", state.number) { onOpen(SettingsPage.Account) }
                    NavRow("Privacy") { onOpen(SettingsPage.Privacy) }
                    NavRow("Notifications") { onOpen(SettingsPage.Notifications) }
                    NavRow("Chats") { onOpen(SettingsPage.Chats) }
                    NavRow("About") { onOpen(SettingsPage.About) }
                }
                SettingsPage.Account -> {
                    ListRow("Number", state.number)
                    ListRow("This device", state.device)
                    ListRow("Connection", state.connection)
                    if (!state.isMain) {
                        NavRow("Transfer history", onClick = actions::transferHistory)
                        NavRow("Make this the main device", onClick = actions::makeMain)
                    }
                }
                SettingsPage.Privacy -> {
                    SwitchRow("Read receipts", state.readReceipts, actions::readReceipts)
                    SwitchRow("Typing indicators", state.typingIndicators, actions::typingIndicators)
                }
                SettingsPage.Notifications -> {
                    SwitchRow("Notifications", state.notifications, actions::notifications)
                    listOf("Name and message", "Name only", "No name or message").forEachIndexed { i, text ->
                        ChoiceRow(text, state.preview == i) { actions.preview(i) }
                    }
                }
                SettingsPage.Chats -> {
                    SwitchRow("Large text", state.largeText, actions::largeText)
                    SwitchRow("Enter key sends", state.enterSends, actions::enterSends)
                }
                SettingsPage.About -> {
                    ListRow("Still", state.version)
                    ListRow(if (state.updateDue) "Update due" else "Up to date", "Signal libraries from ${state.librariesUpdated}")
                    NavRow("Library checks", onClick = actions::checks)
                }
            }
        }
    }
}
