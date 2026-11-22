package com.kongeskog.still.bridge

import org.signal.libsignal.net.ChatConnection
import org.signal.libsignal.net.ChatConnectionListener
import org.signal.libsignal.net.Network
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.zkgroup.groups.ClientZkGroupCipher
import org.signal.libsignal.zkgroup.groups.GroupSecretParams
import org.whispersystems.signalservice.internal.push.Content
import org.whispersystems.signalservice.internal.push.DataMessage
import java.util.Optional
import java.util.concurrent.TimeUnit

data class SpikeStep(val name: String, val ok: Boolean, val detail: String)

/**
 * Milestone 0: proves the Signal libraries load and run on the phone in a minified
 * release build. Uses no account and sends nothing but an unauthenticated connect.
 */
object Spike {
    // signal-cli's convention: name the official client version the library tracks.
    private const val USER_AGENT = "Signal-Android/8.35.1 Still/0.0.1"

    fun run(report: (SpikeStep) -> Unit) {
        step(report, "Load libsignal, identity key") {
            val pair = IdentityKeyPair.generate()
            "public key ${pair.publicKey.serialize().size} bytes"
        }
        step(report, "Post-quantum prekey (Kyber)") {
            val pair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
            "public key ${pair.publicKey.serialize().size} bytes"
        }
        step(report, "Group crypto (zkgroup)") {
            val cipher = ClientZkGroupCipher(GroupSecretParams.generate())
            val plain = "still".toByteArray()
            val back = cipher.decryptBlob(cipher.encryptBlob(plain))
            check(back.contentEquals(plain)) { "round trip mismatch" }
            "encrypt and decrypt match"
        }
        step(report, "Service library protos") {
            val bytes = Content(dataMessage = DataMessage(body = "still")).encode()
            val back = Content.ADAPTER.decode(bytes)
            check(back.dataMessage?.body == "still") { "round trip mismatch" }
            "${bytes.size} bytes"
        }
        step(report, "Java 11 API backport") {
            check(Optional.empty<String>().isEmpty)
            "Optional.isEmpty works"
        }
        step(report, "Connect to Signal (no account)") {
            val network = Network(Network.Environment.PRODUCTION, USER_AGENT)
            val listener = object : ChatConnectionListener {
                override fun onIncomingMessage(
                    chat: ChatConnection, envelope: ByteArray, serverDeliveryTimestamp: Long,
                    sendAck: ChatConnectionListener.ServerMessageAck,
                ) = Unit
            }
            val chat = network.connectUnauthChat(listener).get(30, TimeUnit.SECONDS)
            chat.start()
            val info = chat.info().toString()
            chat.disconnect().get(10, TimeUnit.SECONDS)
            "connected: $info"
        }
    }

    private fun step(report: (SpikeStep) -> Unit, name: String, block: () -> String) {
        val result = try {
            SpikeStep(name, true, block())
        } catch (t: Throwable) {
            android.util.Log.e("StillSpike", name, t)
            SpikeStep(name, false, "${t.javaClass.simpleName}: ${t.message}")
        }
        android.util.Log.i("StillSpike", "${if (result.ok) "OK" else "FAIL"} ${result.name}: ${result.detail}")
        report(result)
    }
}
