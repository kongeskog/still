package com.kongeskog.still.bridge

import io.reactivex.rxjava3.schedulers.Schedulers
import org.signal.core.models.ServiceId.ACI
import org.signal.core.models.ServiceId.PNI
import org.signal.core.util.SleepTimer
import org.signal.core.util.UptimeSleepTimer
import org.signal.libsignal.metadata.certificate.CertificateValidator
import org.signal.libsignal.net.Network
import org.signal.network.api.AttachmentApi
import org.signal.network.api.CallingApi
import org.signal.network.api.CdnApi
import org.signal.network.api.CertificateApi
import org.signal.network.api.KeysApiV2
import org.signal.network.rest.SignalRestClient
import org.signal.network.service.CdnService
import org.signal.network.service.StorageServiceService
import org.whispersystems.signalservice.api.storage.StorageServiceApi
import org.whispersystems.signalservice.api.SignalServiceMessageReceiver
import org.whispersystems.signalservice.api.SignalServiceMessageSender
import org.whispersystems.signalservice.api.groupsv2.ClientZkOperations
import org.whispersystems.signalservice.api.groupsv2.GroupsV2Api
import org.whispersystems.signalservice.api.groupsv2.GroupsV2Operations
import org.whispersystems.signalservice.api.services.ProfileService
import org.whispersystems.signalservice.api.crypto.SignalServiceCipher
import org.whispersystems.signalservice.api.keys.KeysApi
import org.whispersystems.signalservice.api.keys.PreKeyRepository
import org.whispersystems.signalservice.api.message.MessageApi
import org.whispersystems.signalservice.internal.push.PushServiceSocket
import java.util.Optional
import org.whispersystems.signalservice.api.push.SignalServiceAddress
import org.whispersystems.signalservice.api.util.CredentialsProvider
import org.whispersystems.signalservice.api.websocket.HealthMonitor
import org.whispersystems.signalservice.api.websocket.SignalWebSocket
import org.whispersystems.signalservice.api.websocket.WebSocketConnectionState
import org.whispersystems.signalservice.internal.websocket.LibSignalChatConnection
import java.util.concurrent.Executors

/** The linked account's login, read once from the store. */
internal class Credentials(store: AccountStore) : CredentialsProvider {
    private val aci = ACI.parseOrThrow(store.aci ?: error("Not linked"))
    private val pni = store.pni?.let { PNI.parseOrThrow(it) }
    private val number = store.number
    private val device = store.deviceId
    private val secret = store.password ?: error("Not linked")

    override fun getAci(): ACI = aci
    override fun getPni(): PNI? = pni
    override fun getE164(): String? = number
    override fun getDeviceId(): Int = device
    override fun getPassword(): String = secret
}

/**
 * Builds the library objects for the linked account, each once.
 * Ported from signal-cli 0.14.10 `internal/SignalDependencies.java`.
 */
internal class SignalDependencies(
    val credentials: Credentials,
    val dataStore: DataStore,
    private val power: Power,
) {
    val sessionLock = SessionLock()

    val network: Network by lazy {
        Network(SignalEnvironment.netEnvironment, SignalEnvironment.USER_AGENT, emptyMap(), Network.BuildVariant.PRODUCTION)
    }

    val authenticatedWebSocket: SignalWebSocket.AuthenticatedWebSocket by lazy {
        val timer = UptimeSleepTimer()
        val monitor = WebSocketHealthMonitor(power.sleepTimer, power)
        SignalWebSocket.AuthenticatedWebSocket(
            // Stories are out of scope, so the server is told not to send them.
            { LibSignalChatConnection("normal", network, credentials, false, monitor) },
            { true },
            timer,
            30_000,
        ).also(monitor::monitor)
    }

    val unauthenticatedWebSocket: SignalWebSocket.UnauthenticatedWebSocket by lazy {
        val timer = UptimeSleepTimer()
        val monitor = WebSocketHealthMonitor(timer, null)
        SignalWebSocket.UnauthenticatedWebSocket(
            { LibSignalChatConnection("unidentified", network, null, false, monitor) },
            { true },
            timer,
            30_000,
        ).also(monitor::monitor)
    }

    private val pushServiceSocket by lazy {
        PushServiceSocket(SignalEnvironment.configuration, credentials, SignalEnvironment.USER_AGENT, true)
    }
    val groupsV2Operations by lazy { GroupsV2Operations(ClientZkOperations.create(SignalEnvironment.configuration), 1001) }
    val groupsV2Api by lazy { GroupsV2Api(authenticatedWebSocket, pushServiceSocket, groupsV2Operations) }
    val profileService by lazy {
        ProfileService(ClientZkOperations.create(SignalEnvironment.configuration).profileOperations, authenticatedWebSocket, unauthenticatedWebSocket)
    }
    val storageService by lazy { StorageServiceService(StorageServiceApi(authenticatedWebSocket, pushServiceSocket)) }
    val callingApi by lazy { CallingApi(authenticatedWebSocket, unauthenticatedWebSocket, pushServiceSocket) }
    val certificateApi by lazy { CertificateApi(authenticatedWebSocket) }
    val messageReceiver by lazy { SignalServiceMessageReceiver(pushServiceSocket) }
    val keysApi by lazy { KeysApi(authenticatedWebSocket, unauthenticatedWebSocket) }
    val keysApiV2 by lazy { KeysApiV2(authenticatedWebSocket, unauthenticatedWebSocket) }

    val cdnService by lazy {
        val restClient = SignalRestClient(SignalEnvironment.configuration, SignalEnvironment.USER_AGENT, credentials, true)
        CdnService(CdnApi(restClient), AttachmentApi(authenticatedWebSocket)) { null }
    }

    val messageSender: SignalServiceMessageSender by lazy {
        SignalServiceMessageSender(
            pushServiceSocket,
            dataStore,
            sessionLock,
            MessageApi(authenticatedWebSocket, unauthenticatedWebSocket),
            keysApi,
            Optional.empty(),
            Executors.newCachedThreadPool(),
            0, // no envelope size limit, as in signal-cli
            10,
            { true },
            PreKeyRepository(keysApi, dataStore.aci(), credentials.aci.toProtocolAddress(credentials.deviceId), sessionLock) { it.run() },
            cdnService,
        )
    }

    fun cipher(pniAccount: Boolean): SignalServiceCipher {
        val serviceId = if (pniAccount) credentials.pni ?: error("Account has no PNI") else credentials.aci
        return SignalServiceCipher(
            SignalServiceAddress(serviceId, credentials.e164),
            credentials.deviceId,
            if (pniAccount) dataStore.pni() else dataStore.aci(),
            sessionLock,
            CertificateValidator(SignalEnvironment.unidentifiedSenderTrustRoots),
        )
    }
}

/**
 * Sends a keep-alive every 30 seconds and replaces the socket when one goes unanswered
 * for 20. Ported from signal-cli `internal/SignalWebSocketHealthMonitor.java`.
 */
internal class WebSocketHealthMonitor(
    private val sleepTimer: SleepTimer,
    /** Null for sockets that need not survive the phone sleeping. */
    private val power: Power?,
) : HealthMonitor {
    private val executor = Executors.newSingleThreadExecutor()
    private var webSocket: SignalWebSocket? = null
    @Volatile private var sender: KeepAliveSender? = null
    private var needsKeepAlive = false
    @Volatile private var lastKeepAliveReceived = 0L

    fun monitor(socket: SignalWebSocket) {
        executor.execute {
            webSocket = socket
            socket.state.subscribeOn(Schedulers.computation()).observeOn(Schedulers.computation())
                .distinctUntilChanged()
                .subscribe { state ->
                    executor.execute {
                        needsKeepAlive = state == WebSocketConnectionState.CONNECTED
                        updateSender()
                    }
                }
            socket.addKeepAliveChangeListener { executor.execute(::updateSender) }
        }
    }

    override fun onKeepAliveResponse(sentTimestamp: Long, isIdentifiedWebSocket: Boolean) {
        lastKeepAliveReceived = System.currentTimeMillis()
    }
    override fun onMessageError(status: Int, isIdentifiedWebSocket: Boolean) = Unit
    override fun onReceivedAlerts(alerts: Array<out String>, isIdentifiedWebSocket: Boolean) = Unit
    override fun onServerTimestamp(serverTimestamp: Long, isIdentifiedWebSocket: Boolean) = Unit

    private fun shouldSend() = needsKeepAlive && webSocket?.shouldSendKeepAlives() == true

    private fun updateSender() {
        val current = sender
        if (current == null && shouldSend()) {
            sender = KeepAliveSender().also(Thread::start)
        } else if (current != null && !shouldSend()) {
            current.running = false
            sender = null
        }
    }

    private inner class KeepAliveSender : Thread("still-keepalive") {
        @Volatile var running = true

        override fun run() {
            lastKeepAliveReceived = System.currentTimeMillis()
            var sentAt = System.currentTimeMillis()
            while (running && shouldSend()) {
                try {
                    // Screen off: each keep-alive wakes the phone, so they are sent less often.
                    sleepUntil(sentAt + if (power?.isInteractive() == false) SLEEP_CADENCE else SEND_CADENCE)
                    if (!running || !shouldSend()) continue
                    sentAt = System.currentTimeMillis()
                    // Stay awake until the answer is in, or the phone would sleep through it.
                    power?.hold(TIMEOUT)
                    try {
                        webSocket?.sendKeepAlive()
                        val deadline = android.os.SystemClock.uptimeMillis() + TIMEOUT
                        while (lastKeepAliveReceived < sentAt && android.os.SystemClock.uptimeMillis() < deadline) sleep(250)
                    } finally {
                        power?.release()
                    }
                    if (running && shouldSend() && lastKeepAliveReceived < sentAt) {
                        android.util.Log.w("StillSocket", "Keep-alive went unanswered; replacing the socket")
                        webSocket?.forceNewWebSocket()
                    }
                } catch (t: Throwable) {
                    android.util.Log.w("StillSocket", "Keep-alive failed: ${t.message}")
                }
            }
        }

        private fun sleepUntil(time: Long) {
            while (System.currentTimeMillis() < time) {
                try {
                    sleepTimer.sleep(time - System.currentTimeMillis())
                } catch (e: InterruptedException) {
                    return
                }
            }
        }
    }

    private companion object {
        const val SEND_CADENCE = 30_000L
        const val SLEEP_CADENCE = 240_000L
        const val TIMEOUT = 20_000L
    }
}
