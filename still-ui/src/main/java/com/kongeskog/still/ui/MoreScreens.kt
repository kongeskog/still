package com.kongeskog.still.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.lazy.LazyColumnMMD

data class ContactItem(val id: Long, val name: String, val number: String?)

/** People to start a conversation with, narrowed as a name is typed. */
@Composable
fun ContactsScreen(
    contacts: List<ContactItem>, onBack: () -> Unit, title: String = stringResource(R.string.new_message),
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
            placeholder = { TextMMD(stringResource(R.string.search), style = MaterialTheme.typography.bodyLarge) },
        )
        LazyColumnMMD(Modifier.fillMaxSize(), scrollStep = 0, isScrollbarVisible = false) {
            if (onNewGroup != null && query.isBlank()) item(key = "group") { NavRow(stringResource(R.string.new_group), onClick = onNewGroup) }
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
    Screen(stringResource(R.string.new_group), onBack) {
        TextFieldMMD(
            value = name, onValueChange = { name = it.take(32) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            textStyle = MaterialTheme.typography.bodyLarge, singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            placeholder = { TextMMD(stringResource(R.string.group_name), style = MaterialTheme.typography.bodyLarge) },
        )
        LazyColumnMMD(Modifier.weight(1f).fillMaxWidth(), scrollStep = 0, isScrollbarVisible = false) {
            items(contacts.size, key = { contacts[it].id }) { i ->
                val contact = contacts[i]
                ChoiceRow(contact.name, contact.id in picked) { picked = if (contact.id in picked) picked - contact.id else picked + contact.id }
            }
        }
        PrimaryButton(
            if (busy) stringResource(R.string.creating) else stringResource(R.string.create), Modifier.fillMaxWidth().padding(16.dp, 8.dp),
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
                if (state.isGroup && state.canEdit) NavRow(stringResource(R.string.group_name), state.title, onRename)
                if (state.canEdit) NavRow(stringResource(R.string.disappearing_messages), state.timer, onTimer)
                else ListRow(stringResource(R.string.disappearing_messages), state.timer)
                SwitchRow(stringResource(R.string.mute), state.muted, onMute)
                SwitchRow(stringResource(R.string.pin), state.pinned, onPin)
                SwitchRow(stringResource(R.string.archive), state.archived, onArchive)
                SwitchRow(stringResource(R.string.block), state.blocked, onBlock)
                if (!state.isGroup) NavRow(stringResource(R.string.safety_number), onClick = onSafetyNumber)
                if (state.isGroup && state.canEdit) NavRow(stringResource(R.string.add_member), onClick = onAddMember)
                if (state.canLeave) ListRow(if (leaving) stringResource(R.string.tap_again_to_leave) else stringResource(R.string.leave_group), onClick = { if (leaving) onLeave() else leaving = true })
                ListRow(if (confirming) stringResource(R.string.tap_again_to_delete) else stringResource(R.string.delete_conversation), onClick = { if (confirming) onDelete() else confirming = true })
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
        NavRow(stringResource(R.string.message), onClick = onMessage)
        if (canRemove) ListRow(if (confirming) stringResource(R.string.tap_again_to_remove) else stringResource(R.string.remove_from_group), onClick = { if (confirming) onRemove() else confirming = true })
    }
}

/** The sixty digits both people can compare, set in the same four rows of three as Signal shows them. */
@Composable
fun SafetyNumberScreen(name: String, digits: String?, onBack: () -> Unit) {
    Screen(stringResource(R.string.safety_number), onBack, label = name) {
        if (digits == null) {
            TextMMD(stringResource(R.string.no_messages_exchanged_yet), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
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
    Screen(stringResource(R.string.search), onBack) {
        TextFieldMMD(
            value = query, onValueChange = { query = it; onQuery(it) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            textStyle = MaterialTheme.typography.bodyLarge, singleLine = true,
            placeholder = { TextMMD(stringResource(R.string.search_messages), style = MaterialTheme.typography.bodyLarge) },
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
    Screen(stringResource(R.string.disappearing_messages), onBack) {
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
        PrimaryButton(stringResource(R.string.save), Modifier.fillMaxWidth().padding(horizontal = 16.dp), enabled = text.isNotBlank() && text.trim() != current) { onSave(text.trim()) }
    }
}

/** One step that cannot be taken back: a single line saying what it does, and the button. */
@Composable
fun ConfirmScreen(title: String, line: String, action: String, busy: Boolean, error: String?, onBack: () -> Unit, onConfirm: () -> Unit) {
    Screen(title, onBack) {
        TextMMD(error ?: line, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
        PrimaryButton(if (busy) stringResource(R.string.working) else action, Modifier.fillMaxWidth().padding(horizontal = 16.dp), enabled = !busy, onClick = onConfirm)
    }
}

enum class SettingsPage { Main, Account, Devices, Privacy, Notifications, Chats, About }

/** One other device on the account, as the Linked devices screen shows it. */
data class DeviceRow(val id: Int, val name: String, val lastSeen: String)

data class SettingsUi(
    val number: String, val device: String, val connection: String, val version: String,
    /** True once Still is the account's main device rather than a linked one. */
    val isMain: Boolean = false,
    val updateDue: Boolean = false,
    val librariesUpdated: String = "",
    /** Null while they have not been read (or could not be); empty means only this device. */
    val devices: List<DeviceRow>? = null,
    val deviceStatus: String? = null,
    val deviceBusy: Boolean = false,
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
    fun scanDevice()
    fun removeDevice(id: Int)
    fun refreshDevices()
}

/** Settings as plain rows, one topic per screen. Labels only: a row says what it is and nothing more. */
@Composable
fun SettingsScreen(page: SettingsPage, state: SettingsUi, actions: SettingsActions, onBack: () -> Unit, onOpen: (SettingsPage) -> Unit) {
    val title = when (page) {
        SettingsPage.Main -> stringResource(R.string.settings)
        SettingsPage.Account -> stringResource(R.string.account)
        SettingsPage.Devices -> stringResource(R.string.linked_devices)
        SettingsPage.Privacy -> stringResource(R.string.privacy)
        SettingsPage.Notifications -> stringResource(R.string.notifications)
        SettingsPage.Chats -> stringResource(R.string.chats)
        SettingsPage.About -> stringResource(R.string.about)
    }
    Screen(title, onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            when (page) {
                SettingsPage.Main -> {
                    NavRow(stringResource(R.string.account), state.number) { onOpen(SettingsPage.Account) }
                    NavRow(stringResource(R.string.privacy)) { onOpen(SettingsPage.Privacy) }
                    NavRow(stringResource(R.string.notifications)) { onOpen(SettingsPage.Notifications) }
                    NavRow(stringResource(R.string.chats)) { onOpen(SettingsPage.Chats) }
                    NavRow(stringResource(R.string.about)) { onOpen(SettingsPage.About) }
                }
                SettingsPage.Account -> {
                    ListRow(stringResource(R.string.number), state.number)
                    ListRow(stringResource(R.string.this_device), state.device)
                    ListRow(stringResource(R.string.connection), state.connection)
                    if (state.isMain) {
                        NavRow(stringResource(R.string.linked_devices), stringResource(R.string.devices_other, state.devices?.size ?: 0)) { onOpen(SettingsPage.Devices) }
                    }
                    if (!state.isMain) {
                        NavRow(stringResource(R.string.transfer_history), onClick = actions::transferHistory)
                        NavRow(stringResource(R.string.make_this_the_main_device), onClick = actions::makeMain)
                    }
                }
                SettingsPage.Devices -> {
                    NavRow(if (state.deviceBusy) stringResource(R.string.linking) else stringResource(R.string.link_a_new_device)) { actions.scanDevice() }
                    NavRow(stringResource(R.string.refresh)) { actions.refreshDevices() }
                    state.deviceStatus?.let { ListRow(stringResource(R.string.last_result), it) }
                    val devices = state.devices
                    when {
                        devices == null -> ListRow(stringResource(R.string.devices), stringResource(R.string.could_not_be_read))
                        devices.isEmpty() -> ListRow(stringResource(R.string.devices), stringResource(R.string.none))
                        else -> devices.forEach { d ->
                            ListRow(d.name.ifBlank { stringResource(R.string.unnamed_device) }, stringResource(R.string.last_active, d.lastSeen))
                            NavRow(stringResource(R.string.remove_device, d.name.ifBlank { stringResource(R.string.unnamed_device) })) { actions.removeDevice(d.id) }
                        }
                    }
                }
                SettingsPage.Privacy -> {
                    SwitchRow(stringResource(R.string.read_receipts), state.readReceipts, actions::readReceipts)
                    SwitchRow(stringResource(R.string.typing_indicators), state.typingIndicators, actions::typingIndicators)
                }
                SettingsPage.Notifications -> {
                    SwitchRow(stringResource(R.string.notifications), state.notifications, actions::notifications)
                    listOf(stringResource(R.string.name_and_message), stringResource(R.string.name_only), stringResource(R.string.no_name_or_message)).forEachIndexed { i, text ->
                        ChoiceRow(text, state.preview == i) { actions.preview(i) }
                    }
                }
                SettingsPage.Chats -> {
                    SwitchRow(stringResource(R.string.large_text), state.largeText, actions::largeText)
                    SwitchRow(stringResource(R.string.enter_key_sends), state.enterSends, actions::enterSends)
                }
                SettingsPage.About -> {
                    ListRow("Still", state.version)
                    ListRow(if (state.updateDue) stringResource(R.string.update_due) else stringResource(R.string.up_to_date), stringResource(R.string.libraries_from, state.librariesUpdated))
                    NavRow(stringResource(R.string.library_checks), onClick = actions::checks)
                }
            }
        }
    }
}
