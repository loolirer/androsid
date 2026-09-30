package com.androsid

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private val receivingShare = AtomicBoolean(false)
    }

    private val required: Array<String>
        get() {
            val perms = mutableListOf(
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                perms += Manifest.permission.POST_NOTIFICATIONS
            }
            return perms.toTypedArray()
        }

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { startStreaming() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(View(this).apply { setBackgroundColor(Color.BLACK) })

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startStreaming() else requestPermissions.launch(missing.toTypedArray())

        ContextCompat.startForegroundService(this, Intent(this, EnvironmentService::class.java))
        handleIfShared(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIfShared(intent)
    }

    override fun onDestroy() {
        if (isFinishing) {
            stopService(Intent(this, SensorService::class.java))
            stopService(Intent(this, EnvironmentService::class.java))
        }
        super.onDestroy()
    }

    private fun startStreaming() {
        ContextCompat.startForegroundService(this, Intent(this, SensorService::class.java))
    }

    private fun handleIfShared(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND) return
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        }
        if (uri == null) return

        if (!receivingShare.compareAndSet(false, true)) {
            Log.i(TAG, "already receiving a share, ignoring duplicate delivery")
            return
        }

        Thread {
            try {
                val input = contentResolver.openInputStream(uri)?.let(::BufferedInputStream)
                    ?: throw IllegalStateException("could not open shared file")

                input.use {
                    when {
                        isGzip(it) -> {
                            val tarball = saveIncoming(it, "rootfs.tar")
                            Log.i(TAG, "received rootfs tarball at ${tarball.absolutePath}")
                            startEnvironmentAction(EnvironmentService.ACTION_REPLACE_ROOTFS)
                        }
                        isPublicKey(it) -> {
                            val pubkey = saveIncoming(it, "authorized_key.pub")
                            Log.i(TAG, "received SSH public key at ${pubkey.absolutePath}")
                            startEnvironmentAction(EnvironmentService.ACTION_ADD_AUTHORIZED_KEY)
                        }
                        else -> throw IllegalArgumentException("shared file is neither a gzip tarball nor an SSH public key")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "failed to receive shared file", e)
            } finally {
                receivingShare.set(false)
            }
        }.start()
    }

    private fun isGzip(input: BufferedInputStream): Boolean {
        val magic = byteArrayOf(0x1f, 0x8b.toByte())
        return input.peek(magic.size).contentEquals(magic)
    }

    private fun isPublicKey(input: BufferedInputStream): Boolean {
        val prefix = Regex("""^(ssh-(rsa|ed25519|dss)|ecdsa-sha2-nistp(256|384|521))\s""")
        val head = input.peek(32).toString(Charsets.UTF_8)
        return prefix.containsMatchIn(head)
    }

    private fun BufferedInputStream.peek(byteCount: Int): ByteArray {
        mark(byteCount)
        val head = ByteArray(byteCount)
        val read = read(head)
        reset()
        return if (read == byteCount) head else head.copyOf(read.coerceAtLeast(0))
    }

    private fun saveIncoming(input: InputStream, name: String): File {
        val incomingDir = File(filesDir, "incoming").apply { mkdirs() }
        val target = File(incomingDir, name)

        val partial = File(incomingDir, "$name.partial")
        partial.outputStream().use { output -> input.copyTo(output) }
        if (!partial.renameTo(target)) {
            throw IllegalStateException("could not finalize $name")
        }
        return target
    }

    private fun startEnvironmentAction(action: String) {
        val serviceIntent = Intent(this, EnvironmentService::class.java).setAction(action)
        ContextCompat.startForegroundService(this, serviceIntent)
    }
}
