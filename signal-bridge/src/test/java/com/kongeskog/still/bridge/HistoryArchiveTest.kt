package com.kongeskog.still.bridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Builds an archive the way Signal's format describes it and reads it back. */
class HistoryArchiveTest {
    private fun varint(value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var v = value
        while (true) {
            if (v and 0x7f.inv().toLong() == 0L) { out.write(v.toInt()); break }
            out.write(((v and 0x7f) or 0x80).toInt())
            v = v ushr 7
        }
        return out.toByteArray()
    }
    private fun field(number: Int, value: Long) = varint((number.toLong() shl 3) or 0) + varint(value)
    private fun field(number: Int, value: ByteArray) = varint((number.toLong() shl 3) or 2) + varint(value.size.toLong()) + value
    private fun field(number: Int, value: String) = field(number, value.toByteArray())

    private fun archive(records: List<ByteArray>, aesKey: ByteArray, macKey: ByteArray): File {
        val plain = ByteArrayOutputStream()
        GZIPOutputStream(plain).use { gzip -> records.forEach { gzip.write(varint(it.size.toLong())); gzip.write(it) } }
        val iv = ByteArray(16) { it.toByte() }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv)) }
        val body = iv + cipher.doFinal(plain.toByteArray())
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(macKey, "HmacSHA256")) }.doFinal(body)
        return File.createTempFile("archive", null).apply { writeBytes(body + mac); deleteOnExit() }
    }

    @Test
    fun readsBackWhatWasWritten() {
        val aes = ByteArray(32) { 7 }
        val mac = ByteArray(32) { 9 }
        // A chat item: chatId 3, authorId 5, dateSent, standardMessage { text { body } , reaction { emoji, authorId } }
        val text = field(1, "Hello — héllo 👋")
        val reaction = field(1, "👍") + field(2, 5L)
        val standard = field(2, text) + field(6, reaction) + field(6, reaction)
        val item = field(1, 3L) + field(2, 5L) + field(3, 1_763_700_000_123L) + field(11, standard)
        val frames = listOf(field(1, 1L), field(4, item), ByteArray(0))
        val file = archive(frames, aes, mac)

        HistoryArchive.verify(file, mac)
        HistoryArchive.open(file, aes).use { input ->
            assertArrayEquals(frames[0], HistoryArchive.record(input))
            val frame = Proto(HistoryArchive.record(input)!!)
            val parsed = frame.message(4)!!
            assertEquals(3L, parsed.long(1))
            assertEquals(1_763_700_000_123L, parsed.long(3))
            assertTrue(parsed.has(11))
            assertEquals("Hello — héllo 👋", parsed.message(11)!!.message(2)!!.string(1))
            assertEquals(2, parsed.message(11)!!.messages(6).size)
            assertEquals("👍", parsed.message(11)!!.messages(6)[0].string(1))
            assertNull(parsed.long(9))
            assertEquals(0, HistoryArchive.record(input)!!.size)
            assertNull(HistoryArchive.record(input))
        }
    }

    @Test
    fun refusesATamperedArchive() {
        val aes = ByteArray(32) { 1 }
        val mac = ByteArray(32) { 2 }
        val file = archive(listOf(field(1, 1L)), aes, mac)
        val bytes = file.readBytes()
        bytes[20] = (bytes[20].toInt() xor 1).toByte()
        file.writeBytes(bytes)
        assertThrows(SecurityException::class.java) { HistoryArchive.verify(file, mac) }
    }
}
