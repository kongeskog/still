package com.kongeskog.still.bridge

import android.util.Log
import android.webkit.MimeTypeMap
import com.kongeskog.still.bridge.db.AttachmentRow
import com.kongeskog.still.bridge.db.StillDatabase
import org.whispersystems.signalservice.api.crypto.AttachmentCipherInputStream
import org.whispersystems.signalservice.api.util.AttachmentPointerUtil
import org.whispersystems.signalservice.internal.push.AttachmentPointer
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Fetches and decrypts the files attached to messages. Photos and voice notes come down on
 * their own; anything else waits for a tap. From signal-cli `AttachmentHelper.retrieveAttachment`.
 */
internal class Attachments(private val deps: SignalDependencies, db: StillDatabase, private val dir: File, private val cacheDir: File) {
    private val dao = db.chats()
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "still-files").apply { isDaemon = true } }
    private val busy = ConcurrentHashMap.newKeySet<Long>()

    fun fetchNew() = executor.execute {
        dao.attachmentsToFetch(AUTO_MAX_SIZE, System.currentTimeMillis() - 24 * 3600_000L).forEach(::download)
    }

    fun fetch(id: Long) = executor.execute { dao.attachment(id)?.let(::download) }

    private fun download(row: AttachmentRow) {
        if (row.localPath != null || row.pointer == null || !busy.add(row.id)) return
        val temp = File.createTempFile("download", null, cacheDir)
        try {
            val pointer = AttachmentPointerUtil.createSignalAttachmentPointer(AttachmentPointer.ADAPTER.decode(row.pointer))
            val digest = pointer.digest.orElse(null) ?: return
            val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(row.contentType.substringBefore(';')) ?: "bin"
            val target = File(dir.apply { mkdirs() }, "${row.id}.$extension")
            deps.messageReceiver.retrieveAttachment(
                pointer, temp, MAX_SIZE, AttachmentCipherInputStream.IntegrityCheck.forEncryptedDigest(digest),
            ).use { input -> target.outputStream().use { input.copyTo(it) } }
            if (row.contentType.startsWith("text/x-signal-plain")) {
                // A message too long for one envelope: its full text travels as a file.
                dao.setBody(row.messageId, target.readText())
                dao.deleteAttachment(row.id)
                target.delete()
            } else {
                dao.setAttachmentPath(row.id, target.path)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Download failed: ${e.javaClass.simpleName}")
        } finally {
            temp.delete()
            busy.remove(row.id)
        }
    }

    private companion object {
        const val TAG = "StillFiles"
        const val AUTO_MAX_SIZE = 10L * 1024 * 1024
        const val MAX_SIZE = 150L * 1024 * 1024
    }
}
