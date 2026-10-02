package com.androsid

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import kotlin.concurrent.thread

class StreamServer(
    private val socketFile: File,
    private val onCommandReceived: ((String) -> Unit)? = null
) {

    companion object {
        private const val TAG = "StreamServer"
        private const val RETRY_DELAY_MS = 1000L
    }

    private class Client(val socket: LocalSocket) {
        val out: OutputStream = socket.outputStream
    }

    @Volatile private var client: Client? = null
    @Volatile private var server: LocalSocket? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        thread(name = "androsid-accept", isDaemon = true) {
            while (running) {
                try {
                    bindListener().use { listener ->
                        server = listener
                        Log.i(TAG, "listening on '${socketFile.absolutePath}'")
                        val acceptor = LocalServerSocket(listener.fileDescriptor)
                        while (running) {
                            val sock = acceptor.accept()
                            if (!running) {
                                sock.close()
                                break
                            }
                            serve(Client(sock))
                        }
                    }
                } catch (e: Exception) {
                    if (running) {
                        Log.e(TAG, "accept loop died", e)
                        Thread.sleep(RETRY_DELAY_MS)
                    }
                }
            }
        }
    }

    private fun serve(newClient: Client) {
        val previous = synchronized(this) {
            client.also { client = newClient }
        }
        previous?.let {
            Log.i(TAG, "replacing previous client")
            dropClient(it)
        }
        Log.i(TAG, "client connected")

        thread(name = "androsid-reader", isDaemon = true) {
            try {
                val reader = BufferedReader(InputStreamReader(newClient.socket.inputStream, Charsets.UTF_8))
                while (running) {
                    val line = reader.readLine() ?: break
                    if (line.isNotBlank()) onCommandReceived?.invoke(line)
                }
            } catch (e: Exception) {
                if (running) {
                    Log.i(TAG, "client read ended: ${e.message}")
                }
            } finally {
                dropClient(newClient)
            }
        }
    }

    private fun bindListener(): LocalSocket {
        socketFile.parentFile?.mkdirs()
        socketFile.delete()
        return LocalSocket(LocalSocket.SOCKET_STREAM).apply {
            bind(LocalSocketAddress(socketFile.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
        }
    }

    fun stop() {
        running = false
        server?.let { srv ->
            try { Os.shutdown(srv.fileDescriptor, OsConstants.SHUT_RDWR) } catch (_: Exception) {}
            try { srv.close() } catch (_: Exception) {}
        }
        server = null
        socketFile.delete()
        client?.let { dropClient(it) }
    }

    fun isConnected(): Boolean = client != null

    fun send(json: String) =
        sendLine((json + "\n").toByteArray(Charsets.UTF_8))

    fun sendLine(line: ByteArray) {
        val c = client ?: return
        writeTo(c, line)
    }

    private fun writeTo(c: Client, line: ByteArray) {
        try {
            synchronized(c) { c.out.write(line) }
        } catch (e: IOException) {
            Log.i(TAG, "client dropped: ${e.message}")
            dropClient(c)
        }
    }

    private fun dropClient(c: Client) {
        synchronized(this) {
            if (client === c) client = null
        }
        try { c.socket.shutdownInput() } catch (_: Exception) {}
        try { c.socket.shutdownOutput() } catch (_: Exception) {}
        try { c.socket.close() } catch (_: Exception) {}
    }
}
