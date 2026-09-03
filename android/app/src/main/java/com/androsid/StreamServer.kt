package com.androsid

import android.util.Log
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Framed TCP broadcast server.
 *
 * Wire format, big-endian, repeated:
 *
 *     [1 byte  type]
 *     [4 bytes payload length]
 *     [payload]
 *
 * type 0x01  JSON sensor sample, payload is UTF-8 text
 * type 0x02  camera frame, payload is [8 bytes timestamp nanos][JPEG bytes]
 *
 * One socket carries everything so that every stream shares a single clock.
 */
class StreamServer(
    private val port: Int, 
    private val onCommandReceived: ((String) -> Unit)? = null
    ) {

    companion object {
        const val TYPE_JSON: Int = 0x01
        const val TYPE_FRAME: Int = 0x02
        private const val TAG = "StreamServer"
    }

    private class Client(val socket: Socket) {
        val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 64 * 1024))
    }

    private val clients = CopyOnWriteArrayList<Client>()
    private var server: ServerSocket? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        thread(name = "androsid-accept", isDaemon = true) {
            try {
                ServerSocket(port).also { server = it }.use { srv ->
                    Log.i(TAG, "listening on 0.0.0.0:$port")
                    while (running) {
                        val sock = srv.accept()
                        sock.tcpNoDelay = true
                        clients.add(Client(sock))
                        Log.i(TAG, "client connected: ${sock.inetAddress} (${clients.size} total)")

                        thread(name = "androsid-read-${sock.port}", isDaemon = true) {
                            try {
                                val reader = java.io.BufferedReader(java.io.InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
                                while (running) {
                                    val line = reader.readLine() ?: break
                                    onCommandReceived?.invoke(line)
                                }
                            } catch (e: Exception){
                                // Connection dropped by client
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (running) Log.e(TAG, "accept loop died", e)
            }
        }
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        clients.forEach { try { it.socket.close() } catch (_: Exception) {} }
        clients.clear()
    }

    /** True when at least one consumer is attached. Used to skip work nobody wants. */
    fun hasClients(): Boolean = clients.isNotEmpty()

    fun clientCount(): Int = clients.size

    fun broadcastJson(json: String) =
        broadcast(TYPE_JSON, json.toByteArray(Charsets.UTF_8), null)

    fun broadcastFrame(timestampNanos: Long, jpeg: ByteArray) =
        broadcast(TYPE_FRAME, jpeg, timestampNanos)

    private fun broadcast(type: Int, payload: ByteArray, timestampNanos: Long?) {
        if (clients.isEmpty()) return
        val length = payload.size + if (timestampNanos != null) 8 else 0

        for (client in clients) {
            try {
                synchronized(client) {
                    client.out.writeByte(type)
                    client.out.writeInt(length)
                    if (timestampNanos != null) client.out.writeLong(timestampNanos)
                    client.out.write(payload)
                    client.out.flush()
                }
            } catch (e: Exception) {
                Log.i(TAG, "client dropped: ${e.message}")
                clients.remove(client)
                try { client.socket.close() } catch (_: Exception) {}
            }
        }
    }
}
