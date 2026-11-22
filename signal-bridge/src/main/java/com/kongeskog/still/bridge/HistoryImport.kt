package com.kongeskog.still.bridge

import android.util.Log
import com.kongeskog.still.bridge.db.MessageRow
import com.kongeskog.still.bridge.db.ReactionRow
import com.kongeskog.still.bridge.db.RecipientRow
import com.kongeskog.still.bridge.db.StillDatabase
import com.kongeskog.still.bridge.db.ThreadRow
import org.signal.core.models.ServiceId.ACI
import org.signal.core.models.backup.MessageBackupKey
import org.signal.libsignal.zkgroup.groups.GroupMasterKey
import org.signal.libsignal.zkgroup.groups.GroupSecretParams
import org.signal.network.api.LinkDeviceApi
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration.Companion.seconds

/** One protobuf message, read without generated classes: field number to the values seen for it. */
internal class Proto(bytes: ByteArray) {
    private val fields = HashMap<Int, MutableList<Any>>()

    init {
        var at = 0
        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val b = bytes[at++].toInt()
                result = result or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }
        while (at < bytes.size) {
            val tag = varint()
            val number = (tag ushr 3).toInt()
            val value: Any = when ((tag and 7).toInt()) {
                0 -> varint()
                1 -> { at += 8; 0L }
                2 -> { val length = varint().toInt(); bytes.copyOfRange(at, at + length).also { at += length } }
                5 -> { at += 4; 0L }
                else -> throw IllegalArgumentException("Unsupported wire type")
            }
            fields.getOrPut(number) { ArrayList(1) }.add(value)
        }
    }

    fun has(field: Int) = fields.containsKey(field)
    fun long(field: Int): Long? = fields[field]?.lastOrNull() as? Long
    fun bytes(field: Int): ByteArray? = fields[field]?.lastOrNull() as? ByteArray
    fun string(field: Int): String? = bytes(field)?.toString(Charsets.UTF_8)
    fun message(field: Int): Proto? = bytes(field)?.let(::Proto)
    fun messages(field: Int): List<Proto> = fields[field].orEmpty().mapNotNull { (it as? ByteArray)?.let(::Proto) }
}

/** Reading the archive file itself: integrity check, decryption, and splitting into records. */
internal object HistoryArchive {
    private const val MAC_SIZE = 32

    fun verify(file: File, macKey: ByteArray) {
        val length = file.length()
        if (length < MAC_SIZE + 16) throw IllegalArgumentException("Archive too short")
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(macKey, "HmacSHA256")) }
        val expected = ByteArray(MAC_SIZE)
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            var left = length - MAC_SIZE
            while (left > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                if (read < 0) throw EOFException()
                mac.update(buffer, 0, read)
                left -= read
            }
            DataInputStream(input).readFully(expected)
        }
        if (!MessageDigest.isEqual(mac.doFinal(), expected)) throw SecurityException("Archive failed its integrity check")
    }

    fun open(file: File, aesKey: ByteArray): InputStream {
        val input = Limited(file.inputStream(), file.length() - MAC_SIZE)
        val iv = ByteArray(16)
        DataInputStream(input).readFully(iv)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv)) }
        return GZIPInputStream(CipherInputStream(input, cipher)).buffered()
    }

    /** Stops before the trailing MAC. */
    private class Limited(private val input: InputStream, private var left: Long) : InputStream() {
        override fun read(): Int = if (left <= 0) -1 else input.read().also { if (it >= 0) left-- }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            return input.read(b, off, minOf(len.toLong(), left).toInt()).also { if (it > 0) left -= it }
        }
        override fun close() = input.close()
    }

    fun record(input: InputStream): ByteArray? {
        var length = 0
        var shift = 0
        while (true) {
            val b = input.read()
            if (b < 0) return if (shift == 0) null else throw EOFException()
            length = length or ((b and 0x7f) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        return ByteArray(length).also { DataInputStream(input).readFully(it) }
    }
}

/**
 * Brings in the conversations from before Still was linked. When the user agrees to
 * "transfer message history" while linking, the primary device uploads an encrypted archive
 * of its messages and hands this device a one-time key for it.
 *
 * The archive is Signal's backup format (libsignal `rust/message-backup/src/proto/backup.proto`):
 * AES-CBC over gzip over length-prefixed records, with an HMAC at the end. Text, replies,
 * reactions and delivery state are imported; media is not, a word stands in for it.
 */
internal class HistoryImport(
    private val deps: SignalDependencies,
    private val db: StillDatabase,
    private val store: AccountStore,
    private val cacheDir: File,
    private val onProgress: (String?) -> Unit,
) {
    private val dao = db.chats()
    private val selfAci = deps.credentials.aci.toString()

    /** Blocks until the transfer is done or given up on. Safe to call again after a crash. */
    fun runIfPending() {
        val key = store.bytes(KEY) ?: return
        val file = File(cacheDir, "history.archive")
        try {
            onProgress("Waiting for Signal to send history…")
            val archive = waitForArchive() ?: run {
                Log.i(TAG, "No history was offered")
                return finish(file)
            }
            onProgress("Downloading history…")
            deps.messageReceiver.retrieveLinkAndSyncBackup(archive.first, archive.second, file, null).successOrThrow()
            Log.i(TAG, "Downloaded ${file.length() / 1024} KiB of history")
            onProgress("Reading history…")
            val secrets = MessageBackupKey(key).deriveBackupSecrets(deps.credentials.aci, null)
            HistoryArchive.verify(file, secrets.macKey)
            val count = HistoryArchive.open(file, secrets.aesKey).use(::import)
            Log.i(TAG, "Imported $count messages of history")
            finish(file)
        } catch (e: Exception) {
            // Kept for another try at the next start, unless the archive itself is unusable.
            Log.w(TAG, "History import failed: ${e.javaClass.simpleName}")
            if (e is SecurityException || e is IllegalArgumentException) finish(file) else onProgress(null)
        }
    }

    private fun finish(file: File) {
        file.delete()
        db.keyValue().remove(KEY)
        onProgress(null)
    }

    /** The primary uploads after the link is made, which can take minutes for a long history. */
    private fun waitForArchive(): Pair<Int, String>? {
        val api = LinkDeviceApi(deps.authenticatedWebSocket)
        repeat(60) {
            val response = api.waitForPrimaryDevice(30.seconds).successOrNull()
            if (response != null) {
                if (response.error != null) return null
                val cdn = response.cdn
                val key = response.key
                if (cdn != null && key != null) return cdn to key
            }
        }
        return null
    }

    /** What an id in the archive stands for here. */
    private class Target(val recipientId: Long?, val threadId: Long?, val isSelf: Boolean)

    private fun import(input: InputStream): Int {
        HistoryArchive.record(input) ?: return 0 // BackupInfo: nothing in it is needed
        val recipients = HashMap<Long, Target>()
        val chats = HashMap<Long, Long>() // archive chat id -> our thread id
        var messages = 0
        val batch = ArrayList<Proto>(500)
        fun flush() {
            if (batch.isEmpty()) return
            db.runInTransaction {
                for (frame in batch) {
                    try {
                        frame.message(2)?.let { recipient(it, recipients) }
                        frame.message(3)?.let { chat(it, recipients, chats) }
                        frame.message(4)?.let { if (item(it, recipients, chats)) messages++ }
                    } catch (e: Exception) {
                        // One odd record must not cost the rest.
                    }
                }
            }
            batch.clear()
            onProgress("Reading history… $messages messages")
        }
        while (true) {
            batch += Proto(HistoryArchive.record(input) ?: break)
            if (batch.size >= 500) flush()
        }
        flush()
        return messages
    }

    private fun recipient(r: Proto, recipients: HashMap<Long, Target>) {
        val id = r.long(1) ?: return
        r.message(2)?.let { contact ->
            val aci = contact.bytes(1)?.let(ACI::parseOrNull)?.toString() ?: return
            val number = contact.long(4)?.let { "+$it" }
            val existing = dao.recipientByAci(aci)
            val ours = existing?.id ?: dao.insertRecipient(RecipientRow(aci = aci, e164 = number))
            if (existing != null && number != null && existing.e164 == null) dao.setRecipientNumber(ours, number)
            contact.bytes(9)?.takeIf { it.size == 32 }?.let { dao.setRecipientProfileKey(ours, it) }
            val nickname = contact.message(16)?.let { join(it.string(1), it.string(2)) }
            (nickname ?: join(contact.string(18), contact.string(19)))?.let { if (existing?.name == null) dao.setRecipientName(ours, it) }
            join(contact.string(11), contact.string(12))?.let { dao.setProfileNameIfMissing(ours, it) }
            recipients[id] = Target(ours, null, aci == selfAci)
        }
        r.message(3)?.let { group ->
            val key = group.bytes(1)?.takeIf { it.size == 32 } ?: return
            val groupId = Base64.getEncoder().encodeToString(GroupSecretParams.deriveFromMasterKey(GroupMasterKey(key)).publicParams.groupIdentifier.serialize())
            val title = group.message(5)?.message(2)?.string(1)
            val thread = dao.threadByGroup(groupId)?.id ?: dao.insertThread(ThreadRow(groupId = groupId, groupMasterKey = key, title = title))
            recipients[id] = Target(null, thread, false)
        }
        if (r.has(5)) {
            val ours = dao.recipientByAci(selfAci)?.id ?: dao.insertRecipient(RecipientRow(aci = selfAci))
            recipients[id] = Target(ours, null, true)
        }
    }

    private fun chat(c: Proto, recipients: HashMap<Long, Target>, chats: HashMap<Long, Long>) {
        val id = c.long(1) ?: return
        val target = recipients[c.long(2) ?: return] ?: return
        val thread = target.threadId ?: target.recipientId?.let { dao.threadByRecipient(it)?.id ?: dao.insertThread(ThreadRow(recipientId = it)) } ?: return
        chats[id] = thread
        dao.setArchived(thread, c.long(3) == 1L)
        if (c.has(4)) dao.setPinned(thread, true)
        // A group's timer comes from its own state; a person's is recorded on the chat.
        if (target.threadId == null) dao.setExpireTimer(thread, ((c.long(5) ?: 0) / 1000).toInt())
        if ((c.long(6) ?: 0) > System.currentTimeMillis()) dao.setMuted(thread, true)
    }

    private fun item(i: Proto, recipients: HashMap<Long, Target>, chats: HashMap<Long, Long>): Boolean {
        val thread = chats[i.long(1) ?: return false] ?: return false
        val author = recipients[i.long(2) ?: return false] ?: return false
        val sentAt = i.long(3) ?: return false
        val outgoing = i.message(9)
        val senderId = if (outgoing != null || author.isSelf) null else author.recipientId ?: return false

        val standard = i.message(11)
        val attachments = standard?.messages(3).orEmpty()
        val body = when {
            standard != null -> standard.message(2)?.string(1)?.ifBlank { null } ?: attachments.firstOrNull()?.let { file ->
                // Media is not part of the transfer; say what was there.
                when {
                    file.long(2) == 1L -> "Voice note"
                    file.message(1)?.string(4)?.startsWith("image/") == true -> "Photo"
                    else -> "File"
                }
            }
            i.has(13) -> "Sticker"
            i.has(12) -> "Contact"
            i.has(18) -> "View-once media"
            i.has(20) -> "Poll"
            else -> null
        } ?: return false
        if (dao.countMessage(thread, sentAt, senderId) > 0) return false

        val status = outgoing?.messages(1)?.maxOfOrNull { status ->
            when {
                status.has(6) || status.has(7) -> MessageRow.STATUS_READ
                status.has(5) -> MessageRow.STATUS_DELIVERED
                status.has(9) -> MessageRow.STATUS_FAILED
                else -> MessageRow.STATUS_SENT
            }
        } ?: MessageRow.STATUS_SENT
        val quote = standard?.message(1)
        val id = dao.insertMessage(
            MessageRow(
                threadId = thread, senderId = senderId, sentAt = sentAt,
                receivedAt = i.message(8)?.long(1) ?: outgoing?.long(2) ?: sentAt,
                body = body, status = status,
                expiresIn = ((i.long(5) ?: 0) / 1000).toInt(), expireStartedAt = i.long(4),
                quoteText = quote?.let { it.message(3)?.string(1)?.ifBlank { null } ?: "Attachment" },
                quoteAuthorId = quote?.long(2)?.let { recipients[it]?.recipientId },
                quoteSentAt = quote?.long(1),
                edited = i.has(6),
            ),
        )
        for (reaction in standard?.messages(6).orEmpty()) {
            val emoji = reaction.string(1) ?: continue
            val reactor = recipients[reaction.long(2) ?: continue] ?: continue
            dao.putReaction(ReactionRow(id, if (reactor.isSelf) 0 else reactor.recipientId ?: continue, emoji))
        }
        // The list shows the newest message; history must not push an older one in front.
        if (sentAt > (dao.thread(thread)?.lastMessageAt ?: 0)) dao.touchThread(thread, sentAt, body.take(120), 0)
        return true
    }

    private fun join(given: String?, family: String?): String? =
        listOfNotNull(given?.trim(), family?.trim()).filter(String::isNotEmpty).joinToString(" ").ifEmpty { null }

    companion object {
        private const val TAG = "StillHistory"
        /** Key-value entry holding the one-time archive key until the import has run. */
        const val KEY = "history.ephemeral_key"
    }
}
