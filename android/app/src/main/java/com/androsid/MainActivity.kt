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
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {

    companion object {
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
        handleIfSharedTarball(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIfSharedTarball(intent)
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

    private fun handleIfSharedTarball(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND) return
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        }
        if (uri == null) {
            return
        }

        if (!receivingShare.compareAndSet(false, true)) {
            Log.i("MainActivity", "already receiving a share, ignoring duplicate delivery")
            return
        }

        Thread {
            try {
                val incomingDir = File(filesDir, "incoming").apply { mkdirs() }
                val incoming = File(incomingDir, "rootfs.tar")

                val partial = File(incomingDir, "rootfs.tar.partial")
                contentResolver.openInputStream(uri)?.use { input ->
                    partial.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("could not open shared file")
                if (!partial.renameTo(incoming)) {
                    throw IllegalStateException("could not finalize file")
                }

                Log.i("MainActivity", "received rootfs tarball at ${incoming.absolutePath}")

                val replaceIntent = Intent(this, EnvironmentService::class.java)
                    .setAction(EnvironmentService.ACTION_REPLACE_ROOTFS)
                ContextCompat.startForegroundService(this, replaceIntent)
            } catch (e: Exception) {
                Log.e("MainActivity", "failed to receive shared tarball", e)
            } finally {
                receivingShare.set(false)
            }
        }.start()
    }
}
