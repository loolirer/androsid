package com.androsid.sensors

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.androsid.bridge.StreamServer
import java.io.File

abstract class Sensor<T>(
    protected val context: Context,
    socket: File,
) {

    companion object {
        private val bootToEpochNanos: Long =
            System.currentTimeMillis() * 1_000_000L - SystemClock.elapsedRealtimeNanos()
    }

    protected val tag: String = javaClass.simpleName

    protected open val permission: String? = null

    private val server = StreamServer(socket)

    private var started = false

    fun start() {
        val p = permission
        if (p != null &&
            ContextCompat.checkSelfPermission(context, p) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(tag, "$p not granted, $tag disabled")
            return
        }
        server.start()
        onStart()
        started = true
    }

    fun stop() {
        if (!started) return
        started = false
        onStop()
        server.stop()
    }

    protected abstract fun onStart()

    protected abstract fun onStop()

    // Turns one raw event into a wire line, or null when there is nothing to send
    protected abstract fun read(event: T): ByteArray?

    protected fun emit(event: T) {
        if (!server.isConnected()) return
        read(event)?.let(server::sendLine)
    }

    protected fun header(elapsedRealtimeNanos: Long): String =
        """{"stamp":${elapsedRealtimeNanos + bootToEpochNanos},"""

    protected fun message(elapsedRealtimeNanos: Long, fields: String): ByteArray =
        (header(elapsedRealtimeNanos) + fields + "}\n").toByteArray(Charsets.UTF_8)
}
