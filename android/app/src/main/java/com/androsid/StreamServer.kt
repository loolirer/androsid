package com.androsid

import android.util.Log
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class StreamServer(
    private val port: Int,
    private val idleTimeoutMs: Long = 10000L,
    private val onCommandReceived: ((String) -> Unit)? = null
) {

    companion object {
        private const val TAG = "StreamServer"
    }

    private class Client(val socket: Socket) {
        val out = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
        @Volatile var lastSeenAt: Long = System.currentTimeMillis()
    }

    @Volatile private var client: Client? = null
    private var server: ServerSocket? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        thread(name = "androsid-accept", isDaemon = true) {
            while (running) {
                try {
                    ServerSocket(port).use {srv -> 
                        server = srv
                        Log.i(TAG, "listening on 0.0.0.0:$port")
                        
                        val sock = srv.accept()
                        sock.tcpNoDelay = true

                        val currentClient = Client(sock)
                        this.client = currentClient
                        Log.i(TAG, "client connected: ${sock.inetAddress}")

                        thread(name="androsid-reader-${sock.port}", isDaemon = true) {
                            try {
                                val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
                                while (running && !sock.isClosed) {
                                    val line = reader.readLine() ?: break
                                    if (line.isNotBlank()) {
                                        currentClient.lastSeenAt = System.currentTimeMillis()
                                        onCommandReceived?.invoke(line)
                                    }
                                }
                            } catch (e: Exception) {
                                if (running) { 
                                    Log.i(TAG, "Client read ended: ${e.message}") 
                                }
                            } finally {
                                dropClient(currentClient)
                            }
                        }
                    }

                    while (running && client != null) {
                        Thread.sleep(idleTimeoutMs)
                    }
                } catch (e: Exception) {
                    if (running) Log.e(TAG, "accept loop died", e)
                }
            }
        }
    } 


        thread(name = "androsid-watchdog", isDaemon = true) {
            while (running) {
                Thread.sleep(idleTimeoutMs)
                val c = client ?: continue

                val idleMs = System.currentTimeMillis() - c.lastSeenAt
                if (idleMs > idleTimeoutMs) {
                    Log.w(TAG, "client timed out after ${idleMs}ms idle; disconnecting")
                    dropClient(c)
                }
            }
        }
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        client?.let { try { it.socket.close() } catch (_: Exception) {} }
        client = null
    }

    fun isConnected(): Boolean = client != null

    fun broadcast(json: String) =
        broadcastLine((json + "\n").toByteArray(Charsets.UTF_8))

    fun broadcastLine(line: ByteArray) {
        val c = client ?: return
        writeTo(c, line)
    }

    private fun writeTo(c: Client, line: ByteArray) {
        try {
            synchronized(c) {
                c.out.write(line)
                c.out.flush()
            }
            c.lastSeenAt = System.currentTimeMillis()
        } catch (e: Exception) {
            Log.i(TAG, "client dropped: ${e.message}")
            dropClient(c)
        }
    }

    private fun dropClient(c: Client) {
        synchronized(this) {
            if (client === c) client = null
        }
        try { c.socket.close() } catch (_: Exception) {}
    }
}
