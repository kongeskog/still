package com.kongeskog.still.bridge

import android.util.Log
import com.kongeskog.still.bridge.db.EnvelopeRow
import com.kongeskog.still.bridge.db.StillDatabase
import io.reactivex.rxjava3.schedulers.Schedulers
import org.signal.libsignal.metadata.ProtocolException
import org.signal.libsignal.metadata.ProtocolInvalidKeyException
import org.signal.libsignal.metadata.ProtocolInvalidKeyIdException
import org.signal.libsignal.metadata.ProtocolInvalidMessageException
import org.signal.libsignal.metadata.ProtocolNoSessionException
import org.signal.libsignal.protocol.groups.GroupSessionBuilder
import org.signal.libsignal.protocol.message.SenderKeyDistributionMessage
import org.whispersystems.signalservice.api.messages.EnvelopeContentValidator
import org.whispersystems.signalservice.api.messages.EnvelopeResponse
import org.whispersystems.signalservice.api.websocket.WebSocketConnectionState
import org.whispersystems.signalservice.internal.push.AttachmentPointer
import org.whispersystems.signalservice.internal.push.CallMessage
import org.whispersystems.signalservice.internal.push.Content
import org.whispersystems.signalservice.internal.push.Envelope
import org.whispersystems.signalservice.internal.push.TypingMessage
import java.io.IOException
import java.util.concurrent.TimeoutException

/**
 * Reads envelopes from the authenticated socket, stores each before acknowledging it,
 * then decrypts. Follows signal-cli `helper/ReceiveHelper.java`.
 */
internal class ReceiveLoop(
    private val deps: SignalDependencies,
    private val db: StillDatabase,
    private val store: AccountStore,
    private val processor: EnvelopeProcessor,
    private val onNewMessage: (NewMessage) -> Unit,
    private val onState: (String) -> Unit,
    /** Called once, after the first connect has emptied the server's queue. */
    private val onCaughtUp: () -> Unit,
    /** The primary device's contact list arrived as an encrypted attachment. */
    private val onContacts: (AttachmentPointer) -> Unit,
    private val power: Power,
    /** Someone started or stopped typing: their id, the group's id if in a group, and which. */
    private val onTyping: (String, ByteArray?, Boolean) -> Unit,
    /** Called after each batch of envelopes has been filed. */
    private val onFiled: () -> Unit,
    /** A message could not be decrypted: the sender, their device, and what to quote back when asking for it again. */
    private val onUndecryptable: (ProtocolException, Envelope) -> Unit,
    /** A message from someone else arrived: its sender and timestamp, for the delivery receipt. */
    private val onDelivered: (String, Long) -> Unit,
    /** Call signalling: who from, which of their devices, the message, and its age in seconds. */
    private val onCall: (String, Int, CallMessage, Long) -> Unit,
    /** Someone's app could not decrypt a message of ours: who, which device, and their request. */
    private val onResendRequest: (String, Int, ByteArray) -> Unit,
) {
    private var caughtUp = false
    @Volatile private var stopped = false
    private val envelopes = db.envelopes()

    fun stop() { stopped = true }

    /** Blocks; run on its own thread. */
    fun run() {
        val socket = deps.authenticatedWebSocket
        socket.state.observeOn(Schedulers.computation()).distinctUntilChanged().subscribe { state ->
            Log.i(TAG, "Socket $state")
            onState(
                when (state) {
                    WebSocketConnectionState.CONNECTED -> "Connected"
                    WebSocketConnectionState.CONNECTING -> "Connecting…"
                    WebSocketConnectionState.AUTHENTICATION_FAILED -> "Signed out by Signal. Link again."
                    WebSocketConnectionState.REMOTE_DEPRECATED -> "Signal no longer accepts this version. Still needs updating."
                    else -> "Not connected"
                },
            )
            if (state == WebSocketConnectionState.AUTHENTICATION_FAILED || state == WebSocketConnectionState.REMOTE_DEPRECATED) stopped = true
        }
        var backOff = 0
        socket.connect()
        // Without a token the library closes an idle socket after 30 seconds.
        socket.registerKeepAliveToken(KEEP_ALIVE_TOKEN)
        drainCache()
        while (!stopped) {
            try {
                socket.readMessageBatch(60_000, 20) { batch ->
                    // A message can arrive while the phone sleeps; stay awake until it is filed and announced.
                    power.hold(30_000)
                    for (response in batch) {
                        if (response is EnvelopeResponse.Parsed) {
                            // On disk before the ack, so a crash cannot lose it.
                            envelopes.put(EnvelopeRow(envelope = response.envelope.encode(), serverDeliveredTimestamp = response.serverDeliveredTimestamp))
                        } else {
                            Log.w(TAG, "Unparseable envelope ignored")
                        }
                        try {
                            socket.sendAck(response)
                        } catch (e: IOException) {
                            Log.w(TAG, "Ack failed: ${e.message}")
                        }
                    }
                }
                backOff = 0
                drainCache()
                power.release()
                if (!caughtUp) {
                    caughtUp = true
                    onCaughtUp()
                }
            } catch (e: TimeoutException) {
                backOff = 0
            } catch (e: IOException) {
                if (stopped) break
                val wait = 100L shl backOff.coerceAtMost(9)
                backOff++
                Log.w(TAG, "Socket unavailable (${e.javaClass.simpleName}); retrying in $wait ms")
                Thread.sleep(wait)
                try { socket.connect() } catch (e2: Exception) { Log.w(TAG, "Reconnect failed: ${e2.message}") }
            } catch (e: Exception) {
                Log.e(TAG, "Receive error", e)
                Thread.sleep(1000)
            }
        }
        socket.removeKeepAliveToken(KEEP_ALIVE_TOKEN)
        socket.disconnect()
    }

    private fun drainCache() {
        var filed = false
        while (true) {
            val rows = envelopes.oldest(50)
            if (rows.isEmpty()) {
                if (filed) onFiled()
                return
            }
            filed = true
            for (row in rows) {
                try {
                    handle(Envelope.ADAPTER.decode(row.envelope), row.serverDeliveredTimestamp)
                } catch (e: Exception) {
                    // Types only: exception messages from the cipher can name contacts.
                    Log.w(TAG, "Could not process envelope: ${e.javaClass.simpleName}")
                }
                envelopes.remove(row.id)
            }
        }
    }

    private fun handle(envelope: Envelope, serverDeliveredTimestamp: Long) {
        if (envelope.content == null) {
            Log.i(TAG, "Envelope ${envelope.type} without content")
            return
        }
        val toPni = envelope.destinationServiceId?.let { it == store.pni }
            ?: envelope.destinationServiceIdBinary?.let { it.size == 17 && it[0] == 1.toByte() }
            ?: false
        val result = try {
            deps.cipher(toPni).decrypt(envelope, serverDeliveredTimestamp) ?: return
        } catch (e: ProtocolException) {
            // Usually a session that fell out of step. The sender can fix it and send again, if asked.
            if (e is ProtocolInvalidKeyIdException || e is ProtocolInvalidKeyException || e is ProtocolNoSessionException || e is ProtocolInvalidMessageException) {
                Log.w(TAG, "Could not decrypt (${e.javaClass.simpleName}); asking the sender to resend")
                onUndecryptable(e, envelope)
                return
            }
            throw e
        }
        val content = result.content
        val metadata = result.metadata

        val validation = EnvelopeContentValidator.validate(envelope, content, deps.credentials.aci, metadata.ciphertextMessageType)
        if (validation !is EnvelopeContentValidator.Result.Valid) {
            Log.w(TAG, "Invalid content: ${validation.javaClass.simpleName}")
            return
        }

        content.senderKeyDistributionMessage?.let { bytes ->
            deps.sessionLock.acquire().use {
                GroupSessionBuilder(deps.dataStore.aci()).process(
                    metadata.sourceServiceId.toProtocolAddress(metadata.sourceDeviceId),
                    SenderKeyDistributionMessage(bytes.toByteArray()),
                )
            }
        }

        if (content.typingMessage == null) Log.i(TAG, "Decrypted ${kind(content)} (sealed=${metadata.sealedSender})")
        content.syncMessage?.contacts?.blob?.let(onContacts)
        content.typingMessage?.let {
            onTyping(metadata.sourceServiceId.toString(), it.groupId?.toByteArray(), it.action == TypingMessage.Action.STARTED)
            return
        }
        processor.process(content, metadata.sourceServiceId.toString(), metadata.sourceE164)?.let(onNewMessage)
        content.decryptionErrorMessage?.let { onResendRequest(metadata.sourceServiceId.toString(), metadata.sourceDeviceId, it.toByteArray()) }
        content.callMessage?.let { call ->
            val age = ((serverDeliveredTimestamp - (envelope.serverTimestamp ?: serverDeliveredTimestamp)) / 1000).coerceAtLeast(0)
            onCall(metadata.sourceServiceId.toString(), metadata.sourceDeviceId, call, age)
        }
        content.dataMessage?.timestamp?.let { sentAt ->
            if (metadata.sourceServiceId != deps.credentials.aci) onDelivered(metadata.sourceServiceId.toString(), sentAt)
        }
    }

    private fun kind(content: Content): String = when {
        content.dataMessage != null -> "data message"
        content.syncMessage?.sent != null -> "sync: sent transcript"
        content.syncMessage != null -> "sync message"
        content.receiptMessage != null -> "receipt"
        content.typingMessage != null -> "typing"
        content.editMessage != null -> "edit"
        content.callMessage != null -> "call"
        content.storyMessage != null -> "story"
        content.senderKeyDistributionMessage != null -> "sender key"
        else -> "other"
    }

    private companion object {
        const val TAG = "StillReceive"
        const val KEEP_ALIVE_TOKEN = "receive"
    }
}
