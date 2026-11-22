package com.kongeskog.still.bridge

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import org.signal.core.util.SleepTimer

/**
 * Everything that keeps Still working while the phone sleeps: a wake lock for short bursts
 * of work, and a timer whose sleeps end on time even in deep sleep.
 */
internal class Power(private val context: Context) {
    private val manager = context.getSystemService(PowerManager::class.java)
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val lock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "still:work").apply { setReferenceCounted(false) }

    fun isInteractive(): Boolean = manager.isInteractive

    /** Keeps the CPU on for at most [millis]; the timeout is the safety net if [release] is missed. */
    fun hold(millis: Long) = lock.acquire(millis)
    fun release() { if (lock.isHeld) lock.release() }

    /** True once the user has exempted Still from battery optimisation, which Doze needs. */
    fun isUnrestricted(): Boolean = manager.isIgnoringBatteryOptimizations(context.packageName)

    /** The system dialog that asks for that exemption. */
    fun unrestrictedIntent(): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))

    /**
     * A plain thread sleep does not advance while the CPU is suspended. This one also sets
     * an alarm that wakes the phone at the target time. After Signal's `AlarmSleepTimer`.
     */
    val sleepTimer = object : SleepTimer {
        private val monitor = Object()
        private val action = "${context.packageName}.KEEP_ALIVE"
        private val intent = PendingIntent.getBroadcast(
            context, 0, Intent(action).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        init {
            context.registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        // Long enough for the sleeper to wake and take its own hold.
                        hold(5_000)
                        synchronized(monitor) { monitor.notifyAll() }
                    }
                },
                IntentFilter(action),
            )
        }

        override fun sleep(millis: Long) {
            if (millis <= 0) return
            val target = SystemClock.elapsedRealtime() + millis
            // Exact alarms need a permission on Android 12 that the user can revoke.
            try {
                if (alarms.canScheduleExactAlarms()) {
                    alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, target, intent)
                } else {
                    alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, target, intent)
                }
            } catch (e: SecurityException) {
                alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, target, intent)
            }
            synchronized(monitor) {
                while (true) {
                    val left = target - SystemClock.elapsedRealtime()
                    if (left <= 0) break
                    monitor.wait(left)
                }
            }
        }
    }
}
