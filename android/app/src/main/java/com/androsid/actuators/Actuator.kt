package com.androsid.actuators

import android.util.Log
import com.androsid.bridge.StreamServer
import org.json.JSONObject
import java.io.File

abstract class Actuator(socket: File) {

    protected val tag: String = javaClass.simpleName

    private val server = StreamServer(socket) { line -> onCommand(line) }

    fun start() = server.start()

    fun stop() = server.stop()

    protected abstract fun handle(args: JSONObject)

    private fun onCommand(line: String) {
        try {
            handle(JSONObject(line))
        } catch (e: Exception) {
            Log.e(tag, "Error handling incoming command", e)
        }
    }
}
