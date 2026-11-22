package com.kongeskog.still.bridge

import com.kongeskog.still.bridge.db.AttachmentRow
import com.kongeskog.still.bridge.db.MessageRow
import com.kongeskog.still.bridge.db.ReactionRow
import com.kongeskog.still.bridge.db.RecipientRow
import com.kongeskog.still.bridge.db.StillDatabase
import com.kongeskog.still.bridge.db.ThreadRow
import org.signal.core.models.ServiceId
import org.signal.libsignal.zkgroup.groups.GroupMasterKey
import org.signal.libsignal.zkgroup.groups.GroupSecretParams
import org.whispersystems.signalservice.internal.push.CallMessage
import org.whispersystems.signalservice.internal.push.Content
import org.whispersystems.signalservice.internal.push.DataMessage
import org.whispersystems.signalservice.internal.push.EditMessage
import org.whispersystems.signalservice.internal.push.GroupContextV2
import org.whispersystems.signalservice.internal.push.ReceiptMessage
import org.whispersystems.signalservice.internal.push.SyncMessage
import java.util.Base64

/** A message from someone else that was just stored; what a notification needs. */
data class NewMessage(val threadId: Long, val body: String, val messageId: Long = 0)

/**
 * Files decrypted content into recipients, threads and messages. One envelope is one
 * transaction. Only text and attachment counts so far: reactions, edits, deletes, group
 * names and attachments themselves come in later milestones.
 */
internal class EnvelopeProcessor(private val db: StillDatabase, private val selfAci: String) {
    private val dao = db.chats()

    /** Called with a conversation that was just read on another of this account's devices. */
    var onReadElsewhere: (Long) -> Unit = {}

    fun process(content: Content, senderAci: String, senderE164: String?): NewMessage? = db.runInTransaction<NewMessage?> {
        val now = System.currentTimeMillis()
        var created: NewMessage? = null
        if (senderAci != selfAci) {
            content.dataMessage?.let { created = incoming(it, senderAci, senderE164, now) }
            content.editMessage?.let { edit(it, recipient(senderAci, senderE164)) }
            content.callMessage?.offer?.let { created = call(it, senderAci, senderE164, now) }
        }
        content.syncMessage?.let { sync ->
            sync.sent?.let { sent(it, now) }
            sync.sent?.editMessage?.let { edit(it, null) }
            sync.read.forEach(::readElsewhere)
        }
        content.receiptMessage?.let(::receipt)
        created
    }

    /** Files a message written on this phone as "sending"; the send queue takes it from there. */
    fun outgoing(threadId: Long, text: String?, replyTo: Long? = null, file: AttachmentRow? = null): Unit = db.runInTransaction {
        val thread = dao.thread(threadId) ?: return@runInTransaction
        val now = System.currentTimeMillis()
        val quoted = replyTo?.let(dao::message)
        val id = dao.insertMessage(
            MessageRow(
                threadId = threadId, senderId = null, sentAt = now, receivedAt = now, body = text, status = MessageRow.STATUS_SENDING,
                attachmentCount = if (file != null) 1 else 0,
                expiresIn = thread.expireTimer, expireStartedAt = now.takeIf { thread.expireTimer > 0 },
                quoteText = quoted?.let { it.body?.ifBlank { null } ?: "Attachment" },
                quoteAuthorId = quoted?.let { it.senderId ?: recipient(selfAci, null) },
                quoteSentAt = quoted?.sentAt,
            ),
        )
        if (file != null) {
            dao.insertAttachments(listOf(AttachmentRow(messageId = id, pointer = null, contentType = file.contentType, fileName = file.fileName, size = file.size, voiceNote = file.voiceNote, localPath = file.localPath)))
        }
        dao.touchThread(threadId, now, snippet(text, if (file == null) null else if (file.voiceNote) "Voice note" else if (file.contentType.startsWith("image/")) "Photo" else "File"), 0)
    }

    /** Replaces the text of one of our messages; the send queue tells the others. */
    fun editLocally(messageId: Long, text: String, at: Long): Unit = db.runInTransaction {
        val target = dao.message(messageId) ?: return@runInTransaction
        dao.editMessage(messageId, text, at)
        if (dao.lastMessage(target.threadId)?.id == messageId) dao.setSnippet(target.threadId, snippet(text, 0))
    }

    /** Empties a conversation on this phone. A conversation with one person then leaves the list. */
    fun clear(threadId: Long): Unit = db.runInTransaction {
        dao.deleteMessages(threadId)
        cleanUp()
        dao.clearThread(threadId)
    }

    /** Adds a line of news to a conversation's timeline, such as "You left the group". */
    fun note(threadId: Long, text: String): Unit = db.runInTransaction {
        val now = System.currentTimeMillis()
        insertEvent(threadId, null, now, now, text)
    }

    /** Removes a message from this phone only. */
    fun deleteLocally(messageId: Long): Unit = db.runInTransaction {
        val target = dao.message(messageId) ?: return@runInTransaction
        dao.deleteMessage(messageId)
        cleanUp()
        val last = dao.lastMessage(target.threadId)
        dao.setSnippet(target.threadId, last?.let { snippet(it.body, it.attachmentCount) } ?: "")
    }

    /** The conversation with one person, created empty if there is none yet. */
    fun threadWith(recipientId: Long): Long = db.runInTransaction<Long> { directThread(recipientId) }

    private fun incoming(message: DataMessage, senderAci: String, senderE164: String?, now: Long): NewMessage? {
        val sender = recipient(senderAci, senderE164)
        message.profileKey?.let { dao.setRecipientProfileKey(sender, it.toByteArray()) }
        val thread = message.groupV2?.let(::groupThread) ?: directThread(sender)
        if (dao.thread(thread)?.blocked == true) return null
        timer(thread, message)
        val sentAt = message.timestamp ?: return null
        changes(message, sender)
        event(message)?.let { insertEvent(thread, sender, sentAt, now, it) }
        val body = text(message)
        if (!displayable(message, body)) return null
        val id = insert(thread, sender, sentAt, now, message, body, unreadDelta = 1) ?: return null
        return NewMessage(thread, snippet(body, kind(message)), id)
    }

    /** A message this account sent from another device. */
    private fun sent(sent: SyncMessage.Sent, now: Long) {
        val message = sent.message ?: return
        val thread = message.groupV2?.let(::groupThread)
            ?: ServiceId.parseOrNull(sent.destinationServiceId, sent.destinationServiceIdBinary)?.let { directThread(recipient(it.toString(), sent.destinationE164)) }
            ?: return
        timer(thread, message)
        val sentAt = sent.timestamp ?: message.timestamp ?: return
        changes(message, null)
        event(message)?.let { insertEvent(thread, null, sentAt, now, it) }
        val body = text(message)
        if (displayable(message, body)) insert(thread, null, sentAt, now, message, body, unreadDelta = 0, expireStartedAt = sent.expirationStartTimestamp)
        // Replying from another device means the conversation has been read there.
        dao.markMessagesRead(thread, now)
        dao.markThreadRead(thread)
        onReadElsewhere(thread)
    }

    /** Reactions and deletions: messages that change an earlier one instead of adding a new one. */
    private fun changes(message: DataMessage, senderId: Long?) {
        message.reaction?.let { reaction ->
            val author = ServiceId.parseOrNull(reaction.targetAuthorAci, reaction.targetAuthorAciBinary)?.toString() ?: return@let
            val authorId = if (author == selfAci) null else dao.recipientByAci(author)?.id ?: return@let
            val target = dao.messageBy(reaction.targetSentTimestamp ?: return@let, authorId) ?: return@let
            val emoji = reaction.emoji
            if (reaction.remove == true || emoji.isNullOrEmpty()) dao.removeReaction(target.id, senderId ?: 0)
            else dao.putReaction(ReactionRow(target.id, senderId ?: 0, emoji))
        }
        message.delete?.targetSentTimestamp?.let { sentAt ->
            // Only the author can delete: the lookup is by the sender of this delete.
            val target = dao.messageBy(sentAt, senderId) ?: return@let
            dao.deleteMessage(target.id)
            cleanUp()
            val last = dao.lastMessage(target.threadId)
            dao.setSnippet(target.threadId, last?.let { snippet(it.body, it.attachmentCount) } ?: "")
            dao.setThreadUnread(target.threadId, dao.countUnread(target.threadId))
        }
    }

    /** After messages are deleted: drops their reactions, attachment rows and downloaded files. */
    fun cleanUp() {
        dao.deleteOrphanReactions()
        dao.orphanAttachmentPaths().forEach { java.io.File(it).delete() }
        dao.deleteOrphanAttachments()
    }

    private fun edit(edit: EditMessage, senderId: Long?) {
        val target = dao.messageBy(edit.targetSentTimestamp ?: return, senderId) ?: return
        val body = edit.dataMessage?.let(::text) ?: return
        dao.editMessage(target.id, body, edit.dataMessage?.timestamp ?: System.currentTimeMillis())
        if (dao.lastMessage(target.threadId)?.id == target.id) dao.setSnippet(target.threadId, snippet(body, target.attachmentCount))
    }

    private fun readElsewhere(read: SyncMessage.Read) {
        val aci = ServiceId.parseOrNull(read.senderAci, read.senderAciBinary)?.toString() ?: return
        val sender = dao.recipientByAci(aci)?.id ?: return
        val sentAt = read.timestamp ?: return
        val thread = dao.threadOfMessage(sentAt, sender) ?: return
        dao.markMessagesReadUpTo(thread, sentAt, System.currentTimeMillis())
        val left = dao.countUnread(thread)
        dao.setThreadUnread(thread, left)
        if (left == 0) onReadElsewhere(thread)
    }

    private fun receipt(receipt: ReceiptMessage) {
        val status = when (receipt.type) {
            ReceiptMessage.Type.DELIVERY -> MessageRow.STATUS_DELIVERED
            ReceiptMessage.Type.READ -> MessageRow.STATUS_READ
            else -> return
        }
        receipt.timestamp.chunked(500).forEach { dao.raiseStatus(it, status) }
    }

    private fun displayable(message: DataMessage, body: String?) =
        message.reaction == null && message.delete == null &&
            (!body.isNullOrBlank() || message.attachments.isNotEmpty() || message.sticker?.data_ != null)

    /**
     * The text to show for a message: its body with mentions written out, or a word for the
     * kinds of message Still cannot show (stickers, polls, payments, view-once media).
     */
    private fun text(message: DataMessage): String? {
        var body = message.body
        if (body != null) {
            // A mention is one placeholder character in the body plus a range naming the person.
            for (range in message.bodyRanges.filter { it.mentionAci != null || it.mentionAciBinary != null }.sortedByDescending { it.start ?: 0 }) {
                val start = range.start ?: continue
                val end = start + (range.length ?: 1)
                if (start < 0 || end > body!!.length) continue
                val aci = ServiceId.parseOrNull(range.mentionAci, range.mentionAciBinary)?.toString()
                val name = if (aci == selfAci) "you" else aci?.let(dao::nameOf) ?: "someone"
                body = body.substring(0, start) + "@" + name + body.substring(end)
            }
        }
        // A link preview's title, under the link it belongs to.
        message.preview.firstOrNull()?.title?.takeIf { it.isNotBlank() && body?.contains(it) != true }?.let { body = (body.orEmpty() + "\n" + it).trim() }
        if (!body.isNullOrBlank()) return body
        return when {
            message.isViewOnce == true -> "View-once media. Open it in Signal."
            // A sticker is a small picture; it is shown as one when its data came along.
            message.sticker != null -> if (message.sticker?.data_ != null) null else "Sticker"
            message.contact.isNotEmpty() -> "Contact"
            message.pollCreate != null -> "Poll. Open it in Signal."
            message.payment != null -> "Payment"
            message.giftBadge != null -> "Badge"
            else -> null
        }
    }

    /** A line for the timeline when a message is news about the conversation rather than words in it. */
    private fun event(message: DataMessage): String? = when {
        ((message.flags ?: 0) and FLAG_TIMER_UPDATE) != 0 ->
            (message.expireTimer ?: 0).let { if (it == 0) "Disappearing messages turned off" else "Disappearing messages set to ${duration(it)}" }
        message.groupV2?.groupChange != null && message.body == null && message.attachments.isEmpty() && message.reaction == null && message.delete == null -> "Group updated"
        message.groupCallUpdate != null -> "Group call started"
        else -> null
    }

    private fun duration(seconds: Int): String = when {
        seconds % 604800 == 0 -> "${seconds / 604800} wk"
        seconds % 86400 == 0 -> "${seconds / 86400} d"
        seconds % 3600 == 0 -> "${seconds / 3600} h"
        seconds % 60 == 0 -> "${seconds / 60} min"
        else -> "$seconds s"
    }

    private fun insertEvent(thread: Long, senderId: Long?, sentAt: Long, now: Long, text: String, unread: Boolean = false): Long? {
        if (dao.countMessage(thread, sentAt, senderId) > 0) return null
        val id = dao.insertMessage(MessageRow(threadId = thread, senderId = senderId, sentAt = sentAt, receivedAt = now, body = text, kind = 1, unread = unread))
        dao.touchThread(thread, sentAt, text, if (unread) 1 else 0)
        return id
    }

    /** Still cannot take calls. The attempt is recorded, so the user can call back from Signal. */
    private fun call(offer: CallMessage.Offer, senderAci: String, senderE164: String?, now: Long): NewMessage? {
        val thread = directThread(recipient(senderAci, senderE164))
        if (dao.thread(thread)?.blocked == true) return null
        val text = if (offer.type == CallMessage.Offer.Type.OFFER_VIDEO_CALL) "Video call" else "Voice call"
        val id = insertEvent(thread, dao.recipientByAci(senderAci)?.id, offer.id ?: now, now, text, unread = true) ?: return null
        return NewMessage(thread, text, id)
    }

    private fun timer(thread: Long, message: DataMessage) {
        // In a group the timer is part of the group state, which is fetched separately.
        if (message.groupV2 != null) return
        message.expireTimerVersion?.let { version -> dao.thread(thread)?.recipientId?.let { dao.raiseTimerVersion(it, version) } }
        val seconds = message.expireTimer ?: 0
        if (dao.thread(thread)?.expireTimer != seconds) dao.setExpireTimer(thread, seconds)
    }

    /** Returns false if this message is already stored (the server can redeliver). */
    private fun insert(
        thread: Long, senderId: Long?, sentAt: Long, now: Long, message: DataMessage?, body: String?,
        unreadDelta: Int, expireStartedAt: Long? = null,
    ): Long? {
        if (dao.countMessage(thread, sentAt, senderId) > 0) return null
        val attachments = message?.attachments?.size ?: 0
        val id = dao.insertMessage(
            MessageRow(
                threadId = thread, senderId = senderId, sentAt = sentAt, receivedAt = now, body = body,
                attachmentCount = attachments, expiresIn = message?.expireTimer ?: 0,
                // Our own messages start their timer when sent; received ones when read.
                expireStartedAt = expireStartedAt ?: now.takeIf { senderId == null && (message?.expireTimer ?: 0) > 0 },
                unread = unreadDelta > 0,
                quoteText = message?.quote?.let { it.text?.ifBlank { null } ?: "Attachment" },
                quoteAuthorId = message?.quote?.let { ServiceId.parseOrNull(it.authorAci, it.authorAciBinary) }?.let { recipient(it.toString(), null) },
                quoteSentAt = message?.quote?.id,
            ),
        )
        (message?.attachments.orEmpty() + listOfNotNull(message?.sticker?.data_)).takeIf { it.isNotEmpty() && message?.isViewOnce != true }?.let { pointers ->
            dao.insertAttachments(
                pointers.map {
                    AttachmentRow(
                        messageId = id, pointer = it.encode(), contentType = it.contentType ?: "application/octet-stream",
                        fileName = it.fileName, size = (it.size ?: 0).toLong(), voiceNote = ((it.flags ?: 0) and VOICE_FLAG) != 0, localPath = null,
                    )
                },
            )
        }
        dao.touchThread(thread, sentAt, snippet(body, message?.let(::kind)), unreadDelta)
        return id
    }

    /** What stands in for the text of a message that has none. */
    private fun kind(message: DataMessage): String? {
        if (message.sticker != null) return "Sticker"
        val first = message.attachments.firstOrNull() ?: return null
        return when {
            ((first.flags ?: 0) and VOICE_FLAG) != 0 -> "Voice note"
            first.contentType?.startsWith("image/") == true -> "Photo"
            else -> "File"
        }
    }

    private fun snippet(body: String?, kind: String?) = if (!body.isNullOrBlank()) body.take(120) else kind.orEmpty()
    private fun snippet(body: String?, attachments: Int) = snippet(body, "Attachment".takeIf { attachments > 0 })

    private companion object {
        /** AttachmentPointer.Flags.VOICE_MESSAGE */
        const val VOICE_FLAG = 1
        /** DataMessage.Flags.EXPIRATION_TIMER_UPDATE */
        const val FLAG_TIMER_UPDATE = 2
    }

    private fun recipient(aci: String, e164: String?): Long {
        val existing = dao.recipientByAci(aci)
        if (existing == null) return dao.insertRecipient(RecipientRow(aci = aci, e164 = e164))
        if (e164 != null && existing.e164 != e164) dao.setRecipientNumber(existing.id, e164)
        return existing.id
    }

    private fun directThread(recipientId: Long): Long =
        dao.threadByRecipient(recipientId)?.id ?: dao.insertThread(ThreadRow(recipientId = recipientId))

    private fun groupThread(group: GroupContextV2): Long? {
        val bytes = group.masterKey?.toByteArray() ?: return null
        val groupId = Base64.getEncoder().encodeToString(
            GroupSecretParams.deriveFromMasterKey(GroupMasterKey(bytes)).publicParams.groupIdentifier.serialize(),
        )
        val id = dao.threadByGroup(groupId)?.id ?: return dao.insertThread(ThreadRow(groupId = groupId, groupMasterKey = bytes))
        // A newer revision means the title, members or timer may have changed.
        group.revision?.let { dao.markGroupStale(id, it) }
        return id
    }
}
