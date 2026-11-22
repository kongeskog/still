package com.kongeskog.still.bridge

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.util.Log
import com.kongeskog.still.bridge.db.StillDatabase
import org.signal.core.models.ServiceId.ACI
import org.signal.libsignal.protocol.IdentityKey
import org.signal.network.api.CallingApi
import org.signal.ringrtc.AudioConfig
import org.signal.ringrtc.CallId
import org.signal.ringrtc.CallManager
import org.signal.ringrtc.CallSummary
import org.signal.ringrtc.CameraControl
import org.signal.ringrtc.HttpHeader
import org.signal.ringrtc.NetworkRoute
import org.signal.ringrtc.Remote
import org.signal.ringrtc.VideoConfig
import org.webrtc.CapturerObserver
import org.webrtc.EglBase
import org.webrtc.PeerConnection
import org.webrtc.VideoSink
import org.whispersystems.signalservice.api.messages.calls.AnswerMessage
import org.whispersystems.signalservice.api.messages.calls.BusyMessage
import org.whispersystems.signalservice.api.messages.calls.HangupMessage
import org.whispersystems.signalservice.api.messages.calls.IceUpdateMessage
import org.whispersystems.signalservice.api.messages.calls.OfferMessage
import org.whispersystems.signalservice.api.messages.calls.SignalServiceCallMessage
import org.whispersystems.signalservice.api.push.SignalServiceAddress
import org.whispersystems.signalservice.internal.push.CallMessage
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors

enum class CallPhase { Idle, Incoming, Calling, Ringing, Connecting, Active }

/** The one call Still can be in. [connectedAt] is set once both sides can hear each other. */
data class CallState(
    val phase: CallPhase = CallPhase.Idle,
    val threadId: Long = 0,
    val name: String = "",
    val connectedAt: Long? = null,
    val muted: Boolean = false,
    val speaker: Boolean = false,
)

/**
 * One-to-one voice calls on Signal's own calling engine, RingRTC. The engine does the media
 * and decides what to say to the other side; this class carries its messages over the
 * Signal connection, rings, and routes audio. No video, no group calls.
 *
 * After Signal-Android `SignalCallManager` and its `*CallActionProcessor` classes.
 */
internal class Calls(
    private val context: Context,
    private val deps: SignalDependencies,
    private val db: StillDatabase,
    private val store: AccountStore,
    private val power: Power,
    private val sealed: (String) -> org.signal.network.crypto.SealedSenderAccess?,
    private val onState: (CallState) -> Unit,
) : CallManager.Observer {

    private class Peer(val aci: ACI, val threadId: Long, val name: String, var callId: CallId) : Remote {
        override fun recipientEquals(other: Remote): Boolean = other is Peer && other.aci == aci
    }

    // The engine is not thread-safe; everything that touches it runs here.
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "still-call").apply { isDaemon = true } }
    private val audio = context.getSystemService(AudioManager::class.java)
    private val manager: CallManager? = try {
        CallManager.initialize(context, EngineLog, emptyMap())
        CallManager.createCallManager(this)?.also { it.setSelfUuid(deps.credentials.aci.rawUuid) }
    } catch (t: Throwable) {
        Log.w(TAG, "Calling engine unavailable: ${t.javaClass.simpleName}")
        null
    }
    private val egl: EglBase? by lazy { try { EglBase.create() } catch (t: Throwable) { null } }
    private var peer: Peer? = null
    private var state = CallState()
    private var ringtone: Ringtone? = null

    private fun update(change: (CallState) -> CallState) {
        state = change(state)
        onState(state)
    }

    private fun run(what: String, block: (CallManager) -> Unit) = executor.execute {
        try {
            manager?.let(block)
        } catch (t: Throwable) {
            Log.w(TAG, "$what failed: ${t.javaClass.simpleName}")
        }
    }

    private fun peerFor(aci: ACI, callId: Long): Peer? {
        val dao = db.chats()
        val recipient = dao.recipientByAci(aci.toString()) ?: return null
        val thread = dao.threadByRecipient(recipient.id) ?: return null
        if (thread.blocked) return null
        return Peer(aci, thread.id, recipient.name ?: recipient.profileName ?: recipient.e164 ?: "Unknown", CallId(callId))
    }

    /** The raw 32-byte public keys the engine mixes into the call's encryption. */
    private fun keys(aci: ACI): Pair<ByteArray, ByteArray>? {
        val theirs = db.sessions().identity(0, aci.toString())?.identityKey?.let(::IdentityKey) ?: return null
        val ours = store.identity(0)?.publicKey ?: return null
        return theirs.publicKey.publicKeyBytes to ours.publicKey.publicKeyBytes
    }

    /** Signalling from the other side, as it comes off the Signal connection. */
    fun received(senderAci: String, senderDevice: Int, message: CallMessage, ageSeconds: Long) = run("Call signalling") { engine ->
        val aci = ACI.parseOrNull(senderAci) ?: return@run
        message.offer?.let { offer ->
            val id = offer.id ?: return@let
            val remote = peerFor(aci, id) ?: return@let
            val (theirs, ours) = keys(aci) ?: return@let
            val type = if (offer.type == CallMessage.Offer.Type.OFFER_VIDEO_CALL) CallManager.CallMediaType.VIDEO_CALL else CallManager.CallMediaType.AUDIO_CALL
            if (peer == null) peer = remote
            engine.receivedOffer(remote.callId, remote, senderDevice, offer.opaque?.toByteArray() ?: return@let, ageSeconds, type, deps.credentials.deviceId, theirs, ours)
        }
        val current = peer?.takeIf { it.aci == aci }
        message.answer?.let { answer ->
            val remote = current ?: return@let
            val (theirs, ours) = keys(aci) ?: return@let
            engine.receivedAnswer(CallId(answer.id ?: return@let), remote, senderDevice, answer.opaque?.toByteArray() ?: return@let, theirs, ours)
        }
        if (message.iceUpdate.isNotEmpty()) {
            val remote = current ?: peerFor(aci, message.iceUpdate.first().id ?: 0)
            val id = message.iceUpdate.first().id
            if (remote != null && id != null) engine.receivedIceCandidates(CallId(id), remote, senderDevice, message.iceUpdate.mapNotNull { it.opaque?.toByteArray() })
        }
        message.hangup?.let { hangup ->
            val remote = current ?: peerFor(aci, hangup.id ?: 0) ?: return@let
            val type = when (hangup.type) {
                CallMessage.Hangup.Type.HANGUP_ACCEPTED -> CallManager.HangupType.ACCEPTED
                CallMessage.Hangup.Type.HANGUP_DECLINED -> CallManager.HangupType.DECLINED
                CallMessage.Hangup.Type.HANGUP_BUSY -> CallManager.HangupType.BUSY
                CallMessage.Hangup.Type.HANGUP_NEED_PERMISSION -> CallManager.HangupType.NEED_PERMISSION
                else -> CallManager.HangupType.NORMAL
            }
            engine.receivedHangup(CallId(hangup.id ?: return@let), remote, senderDevice, type, hangup.deviceId ?: 0)
        }
        message.busy?.let { busy -> current?.let { engine.receivedBusy(CallId(busy.id ?: return@let), it, senderDevice) } }
    }

    /** Starts a voice call to the person a conversation is with. */
    fun start(threadId: Long) = run("Starting a call") { engine ->
        if (peer != null) return@run
        val dao = db.chats()
        val recipient = dao.thread(threadId)?.recipientId?.let(dao::recipient) ?: return@run
        val aci = ACI.parseOrNull(recipient.aci) ?: return@run
        if (aci == deps.credentials.aci) return@run
        val remote = Peer(aci, threadId, recipient.name ?: recipient.profileName ?: recipient.e164 ?: "Unknown", CallId(java.security.SecureRandom().nextLong()))
        peer = remote
        update { CallState(CallPhase.Calling, threadId, remote.name) }
        engine.call(remote, CallManager.CallMediaType.AUDIO_CALL, deps.credentials.deviceId)
    }

    fun accept() = run("Answering") { engine ->
        val remote = peer ?: return@run
        stopRinging()
        update { it.copy(phase = CallPhase.Connecting) }
        engine.acceptCall(remote.callId)
    }

    /** Declines a ringing call or ends the one in progress. */
    fun hangUp() = run("Hanging up") { engine ->
        if (peer == null) return@run
        engine.hangup()
    }

    fun setMuted(muted: Boolean) = run("Muting") { engine ->
        engine.setAudioEnable(!muted)
        update { it.copy(muted = muted) }
    }

    fun setSpeaker(on: Boolean) = executor.execute {
        @Suppress("DEPRECATION")
        audio.isSpeakerphoneOn = on
        update { it.copy(speaker = on) }
    }

    // What the engine asks of us

    override fun onStartCall(remote: Remote?, callId: CallId, isOutgoing: Boolean?, mediaType: CallManager.CallMediaType?) {
        val current = remote as? Peer ?: return
        executor.execute {
            current.callId = callId
            peer = current
            power.hold(2 * 3600_000L)
            try {
                val servers = deps.callingApi.getTurnServerInfo().successOrThrow().flatMap { info ->
                    info.urlsWithIps.orEmpty().map { PeerConnection.IceServer.builder(it).setUsername(info.username).setPassword(info.password).setHostname(info.hostname).createIceServer() } +
                        info.urls.orEmpty().map { PeerConnection.IceServer.builder(it).setUsername(info.username).setPassword(info.password).createIceServer() }
                }
                manager?.proceed(
                    callId, context, egl ?: throw IllegalStateException("No graphics context"), AudioConfig(), VideoConfig(), NoVideo, NoVideo, NoCamera, servers,
                    false, CallManager.DataMode.NORMAL, null, 0.toByte(), false, null,
                )
            } catch (t: Throwable) {
                Log.w(TAG, "Could not set up the call: ${t.javaClass.simpleName}")
                try { manager?.drop(callId) } catch (e: Throwable) { }
                ended()
            }
        }
    }

    override fun onCallEvent(remote: Remote?, event: CallManager.CallEvent) {
        val current = remote as? Peer ?: return
        Log.i(TAG, "Call event ${event.name}")
        executor.execute {
            when (event) {
                CallManager.CallEvent.LOCAL_RINGING -> {
                    update { CallState(CallPhase.Incoming, current.threadId, current.name) }
                    startRinging()
                    Notifier.incomingCall(context, current.threadId, current.name)
                }
                CallManager.CallEvent.REMOTE_RINGING -> update { it.copy(phase = CallPhase.Ringing) }
                CallManager.CallEvent.LOCAL_CONNECTED, CallManager.CallEvent.REMOTE_CONNECTED -> {
                    stopRinging()
                    audio.mode = AudioManager.MODE_IN_COMMUNICATION
                    try { manager?.setAudioEnable(!state.muted) } catch (t: Throwable) { }
                    if (state.connectedAt == null) update { it.copy(phase = CallPhase.Active, connectedAt = System.currentTimeMillis()) }
                }
                else -> Unit
            }
        }
    }

    override fun onCallEnded(remote: Remote?, reason: CallManager.CallEndReason, summary: CallSummary) {
        Log.i(TAG, "Call ended: ${reason.name}")
        executor.execute {
            // An offer that arrives during a call ends at once as "busy"; that must not end the real call.
            if (remote is Peer && peer != null && remote !== peer && remote.callId != peer?.callId) return@execute
            ended()
        }
    }

    private fun ended() {
        stopRinging()
        Notifier.cancelCall(context)
        @Suppress("DEPRECATION")
        audio.isSpeakerphoneOn = false
        audio.mode = AudioManager.MODE_NORMAL
        power.release()
        peer = null
        update { CallState() }
    }

    override fun onCallConcluded(remote: Remote?) = Unit
    override fun onNetworkRouteChanged(remote: Remote?, route: NetworkRoute?) = Unit
    override fun onAudioLevels(remote: Remote?, captured: Int, received: Int) = Unit
    override fun onLowBandwidthForVideo(remote: Remote?, recovered: Boolean) = Unit

    private fun send(callId: CallId, remote: Remote?, message: SignalServiceCallMessage) {
        val current = remote as? Peer ?: return
        // Off the engine's thread: sending waits on the network.
        Thread {
            val ok = try {
                deps.messageSender.sendCallMessage(SignalServiceAddress(current.aci), sealed(current.aci.toString()), message).isSuccess
            } catch (t: Throwable) {
                Log.w(TAG, "Call signalling not sent: ${t.javaClass.simpleName}")
                false
            }
            run("Reporting a send") { if (ok) it.messageSent(callId) else it.messageSendFailure(callId) }
        }.start()
    }

    private fun target(device: Int?, broadcast: Boolean?): Int? = if (broadcast == true) null else device

    override fun onSendOffer(callId: CallId, remote: Remote?, device: Int?, broadcast: Boolean?, opaque: ByteArray, mediaType: CallManager.CallMediaType?) {
        val type = if (mediaType == CallManager.CallMediaType.VIDEO_CALL) OfferMessage.Type.VIDEO_CALL else OfferMessage.Type.AUDIO_CALL
        send(callId, remote, SignalServiceCallMessage.forOffer(OfferMessage(callId.longValue(), type, opaque), target(device, broadcast)))
    }

    override fun onSendAnswer(callId: CallId, remote: Remote?, device: Int?, broadcast: Boolean?, opaque: ByteArray) =
        send(callId, remote, SignalServiceCallMessage.forAnswer(AnswerMessage(callId.longValue(), opaque), target(device, broadcast)))

    override fun onSendIceCandidates(callId: CallId, remote: Remote?, device: Int?, broadcast: Boolean?, candidates: MutableList<ByteArray>) =
        send(callId, remote, SignalServiceCallMessage.forIceUpdates(candidates.map { IceUpdateMessage(callId.longValue(), it) }, target(device, broadcast)))

    override fun onSendHangup(callId: CallId, remote: Remote?, device: Int?, broadcast: Boolean?, type: CallManager.HangupType?, deviceId: Int?) {
        val kind = when (type) {
            CallManager.HangupType.ACCEPTED -> HangupMessage.Type.ACCEPTED
            CallManager.HangupType.DECLINED -> HangupMessage.Type.DECLINED
            CallManager.HangupType.BUSY -> HangupMessage.Type.BUSY
            CallManager.HangupType.NEED_PERMISSION -> HangupMessage.Type.NEED_PERMISSION
            else -> HangupMessage.Type.NORMAL
        }
        send(callId, remote, SignalServiceCallMessage.forHangup(HangupMessage(callId.longValue(), kind, deviceId ?: 0), target(device, broadcast)))
    }

    override fun onSendBusy(callId: CallId, remote: Remote?, device: Int?, broadcast: Boolean?) =
        send(callId, remote, SignalServiceCallMessage.forBusy(BusyMessage(callId.longValue()), target(device, broadcast)))

    // Group calls and call links are not supported.
    override fun onSendCallMessage(recipient: UUID, message: ByteArray, urgency: CallManager.CallMessageUrgency) = Unit
    override fun onSendCallMessageToGroup(groupId: ByteArray, message: ByteArray, urgency: CallManager.CallMessageUrgency, overrideRecipients: MutableList<UUID>) = Unit
    override fun onSendCallMessageToAdhocGroup(message: ByteArray, urgency: CallManager.CallMessageUrgency, expiration: Instant, recipients: MutableMap<UUID, ByteArray>) = Unit
    override fun onSendHttpRequest(requestId: Long, url: String, method: CallManager.HttpMethod, headers: MutableList<HttpHeader>?, body: ByteArray?) =
        run("Refusing a group call request") { it.httpRequestFailed(requestId) }
    override fun onGroupCallRingUpdate(groupId: ByteArray, ringId: Long, sender: UUID, update: CallManager.RingUpdate?) = Unit

    private fun startRinging() {
        if (ringtone != null) return
        try {
            ringtone = RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))?.apply {
                audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build()
                isLooping = true
                play()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "No ringtone: ${t.javaClass.simpleName}")
        }
    }

    private fun stopRinging() {
        try { ringtone?.stop() } catch (t: Throwable) { }
        ringtone = null
    }

    private object NoVideo : VideoSink { override fun onFrame(frame: org.webrtc.VideoFrame?) = Unit }

    private object NoCamera : CameraControl {
        override fun hasCapturer() = false
        override fun initCapturer(observer: CapturerObserver) = Unit
        override fun setEnabled(enabled: Boolean) = Unit
        override fun flip() = Unit
        override fun setOrientation(orientation: Int?) = Unit
    }

    /** The engine is chatty and its lines can carry identifiers; only its warnings are kept. */
    private object EngineLog : org.signal.ringrtc.Log.Logger {
        override fun v(tag: String?, message: String?, t: Throwable?) = Unit
        override fun d(tag: String?, message: String?, t: Throwable?) = Unit
        override fun i(tag: String?, message: String?, t: Throwable?) = Unit
        override fun w(tag: String?, message: String?, t: Throwable?) { Log.w(TAG, "engine warning in $tag") }
        override fun e(tag: String?, message: String?, t: Throwable?) { Log.e(TAG, "engine error in $tag: ${t?.javaClass?.simpleName ?: ""}") }
    }

    private companion object {
        const val TAG = "StillCall"
    }
}
