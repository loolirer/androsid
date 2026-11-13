package com.androsid.sensors

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.androsid.bridge.StreamServer

abstract class Sensor<T>(
    protected val context: Context,
    protected val server: StreamServer,
) {

    companion object {
        private val bootToEpochNanos: Long =
            System.currentTimeMillis() * 1_000_000L - SystemClock.elapsedRealtimeNanos()
    }

    protected val tag: String = javaClass.simpleName

    protected open val permission: String? = null

    private var started = false

    protected val isStarted: Boolean
        get() = started

    fun start() {
        val p = permission
        if (p != null &&
            ContextCompat.checkSelfPermission(context, p) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(tag, "$p not granted, $tag disabled")
            return
        }
        onStart()
        started = true
    }

    fun stop() {
        if (!started) return
        started = false
        onStop()
    }

    protected abstract fun onStart()

    protected abstract fun onStop()

    // Turns one raw event into a wire line, or null when there is nothing to send
    protected abstract fun read(event: T): ByteArray?

    protected fun emit(event: T) {
        read(event)?.let(server::sendLine)
    }

    protected fun header(type: String, elapsedRealtimeNanos: Long): String =
        """{"type":"$type","stamp":${elapsedRealtimeNanos + bootToEpochNanos},"""

    protected fun message(type: String, elapsedRealtimeNanos: Long, fields: String): ByteArray =
        (header(type, elapsedRealtimeNanos) + fields + "}\n").toByteArray(Charsets.UTF_8)
}
