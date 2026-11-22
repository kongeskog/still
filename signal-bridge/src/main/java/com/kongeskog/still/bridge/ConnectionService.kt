package com.kongeskog.still.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the process, and with it the socket to Signal, alive in the background. There is
 * no push service on this phone, so an open connection is the only way to hear of a message.
 */
class ConnectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
        startForeground(Notifier.CONNECTION_ID, Notifier.connection(this, "Connecting…"))
        val client = SignalClient.get(this)
        scope.launch {
            if (!client.load()) {
                stopSelf()
                return@launch
            }
            // One update per state change; the text is all that redraws.
            client.connection.collect { state ->
                getSystemService(NotificationManager::class.java).notify(Notifier.CONNECTION_ID, Notifier.connection(this@ConnectionService, state))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Called again when a call is answered from the screen: only a service put in the
        // foreground while the app is visible may keep the microphone once the screen is off.
        startForeground(Notifier.CONNECTION_ID, Notifier.connection(this, SignalClient.get(this).connection.value))
        return START_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** Safe to call repeatedly. On Android 12 it must be called from the foreground or from boot. */
        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, ConnectionService::class.java))
            } catch (e: Exception) {
                Log.w("StillService", "Could not start the connection service: ${e.javaClass.simpleName}")
            }
        }
    }
}

/** Restarts the connection after a reboot or an app update. Starting does nothing if no account is linked. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ConnectionService.start(context)
    }
}

internal object Notifier {
    const val CONNECTION_ID = 1
    private const val CONNECTION_CHANNEL = "connection"
    private const val MESSAGE_CHANNEL = "messages"
    const val EXTRA_THREAD = "thread"
    /** Thread notifications use the thread id, offset past the fixed ids. */
    private const val THREAD_ID_BASE = 1000

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CONNECTION_CHANNEL, "Connection", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown while Still is connected to Signal"
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(NotificationChannel(MESSAGE_CHANNEL, "Messages", NotificationManager.IMPORTANCE_HIGH))
    }

    fun connection(context: Context, state: String): Notification =
        Notification.Builder(context, CONNECTION_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_still)
            .setContentTitle("Still")
            .setContentText(state)
            .setOngoing(true)
            .setContentIntent(open(context, null))
            .build()

    /**
     * MessagingStyle, so the lock-screen line from the Signal Lock app can read the
     * conversation title and message the same way it reads Signal's.
     */
    fun message(context: Context, threadId: Long, title: String, sender: String, isGroup: Boolean, body: String, time: Long) {
        createChannels(context)
        val person = Person.Builder().setName(sender).build()
        val style = Notification.MessagingStyle(Person.Builder().setName("You").build())
            .setGroupConversation(isGroup)
            .addMessage(body, time, person)
        if (isGroup) style.setConversationTitle(title)
        val notification = Notification.Builder(context, MESSAGE_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_still)
            .setStyle(style)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setWhen(time)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(open(context, threadId))
            .build()
        context.getSystemService(NotificationManager::class.java).notify(THREAD_ID_BASE + threadId.toInt(), notification)
    }

    private const val CALL_CHANNEL = "calls"
    private const val CALL_ID = 2

    /** Rings through the lock screen: the notification opens the call screen full-screen. */
    fun incomingCall(context: Context, threadId: Long, name: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CALL_CHANNEL, "Calls", NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null) })
        val intent = open(context, threadId)
        manager.notify(
            CALL_ID,
            Notification.Builder(context, CALL_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_still)
                .setContentTitle(name)
                .setContentText("Incoming call")
                .setCategory(Notification.CATEGORY_CALL)
                .setOngoing(true)
                .setContentIntent(intent)
                .setFullScreenIntent(intent, true)
                .build(),
        )
    }

    fun cancelCall(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(CALL_ID)

    fun cancel(context: Context, threadId: Long) =
        context.getSystemService(NotificationManager::class.java).cancel(THREAD_ID_BASE + threadId.toInt())

    private fun open(context: Context, threadId: Long?): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        if (threadId != null) intent.putExtra(EXTRA_THREAD, threadId)
        return PendingIntent.getActivity(
            context, threadId?.toInt() ?: 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
