package com.kongeskog.still

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.FileProvider
import java.io.File

/** Where files that Still sends or receives live. They are deleted with their message. */
private fun attachmentsDir(context: Context) = File(context.filesDir, "attachments").apply { mkdirs() }

class Imported(val path: String, val contentType: String, val name: String?)

/** Copies a picked document into Still's own storage, so it can be sent after the picker's grant ends. */
fun importUri(context: Context, uri: Uri): Imported? = try {
    val resolver = context.contentResolver
    val type = resolver.getType(uri) ?: "application/octet-stream"
    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    }
    val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(type) ?: name?.substringAfterLast('.', "bin") ?: "bin"
    val target = File(attachmentsDir(context), "out-${System.currentTimeMillis()}.$extension")
    resolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
    if (target.length() > 0) Imported(target.path, type, name) else null
} catch (e: Exception) {
    null
}

/** Hands a received file to whichever app can show it. Returns false if none can. */
fun openFile(context: Context, path: String, contentType: String): Boolean = try {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", File(path))
    context.startActivity(
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, contentType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
    )
    true
} catch (e: Exception) {
    false
}

/** Records one voice note at a time, in the AAC format Signal uses for them. */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    /** When the current recording began, or null. Compose state, so the composer follows it. */
    val since = mutableStateOf<Long?>(null)

    fun start(): Boolean = try {
        val target = File(attachmentsDir(context), "out-${System.currentTimeMillis()}.m4a")
        recorder = MediaRecorder(context).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioChannels(1)
            setAudioSamplingRate(44_100)
            setAudioEncodingBitRate(32_000)
            setOutputFile(target.path)
            prepare()
            start()
        }
        file = target
        since.value = System.currentTimeMillis()
        true
    } catch (e: Exception) {
        release()
        false
    }

    /** Ends the recording and returns its file, or null if nothing usable was recorded. */
    fun finish(): File? {
        val result = file
        val ok = try {
            recorder?.stop()
            true
        } catch (e: Exception) {
            false // stopped before any audio arrived
        }
        release()
        if (!ok || result == null || result.length() == 0L) {
            result?.delete()
            return null
        }
        return result
    }

    fun cancel() {
        try { recorder?.stop() } catch (e: Exception) { }
        file?.delete()
        release()
    }

    private fun release() {
        recorder?.release()
        recorder = null
        file = null
        since.value = null
    }
}

/** Plays one voice note at a time. */
class VoicePlayer {
    private var player: MediaPlayer? = null
    /** The attachment that is playing, or null. */
    val playing = mutableStateOf<Long?>(null)

    fun toggle(id: Long, path: String) {
        val wasPlaying = playing.value == id
        stop()
        if (wasPlaying) return
        try {
            player = MediaPlayer().apply {
                setDataSource(path)
                setOnCompletionListener { stop() }
                prepare()
                start()
            }
            playing.value = id
        } catch (e: Exception) {
            stop()
        }
    }

    fun stop() {
        player?.release()
        player = null
        playing.value = null
    }
}
