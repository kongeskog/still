package com.kongeskog.still.bridge

import android.util.Log
import org.signal.libsignal.protocol.logging.SignalProtocolLogger
import org.signal.libsignal.protocol.logging.SignalProtocolLoggerProvider
import org.signal.core.util.logging.Log as SignalLog

/**
 * Sends the service library's and libsignal's logs to logcat. Without this they are
 * discarded, and failures inside the library are invisible.
 */
internal object Logging {
    @Volatile private var installed = false

    fun install() {
        if (installed) return
        installed = true
        SignalLog.initialize(object : SignalLog.Logger() {
            override fun v(tag: String, message: String?, t: Throwable?, keepLonger: Boolean) = Unit
            override fun d(tag: String, message: String?, t: Throwable?, keepLonger: Boolean) = Unit
            override fun i(tag: String, message: String?, t: Throwable?, keepLonger: Boolean) { Log.i(tag, message, t) }
            override fun w(tag: String, message: String?, t: Throwable?, keepLonger: Boolean) { Log.w(tag, message, t) }
            override fun e(tag: String, message: String?, t: Throwable?, keepLonger: Boolean) { Log.e(tag, message, t) }
            override fun flush() = Unit
        })
        SignalProtocolLoggerProvider.setProvider(SignalProtocolLogger { priority, tag, message ->
            if (priority >= SignalProtocolLogger.INFO) Log.println(priority, tag, message)
        })
    }
}
