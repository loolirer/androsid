package com.androsid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.system.Os
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import java.io.File
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean

class EnvironmentService : LifecycleService() {

    companion object {
        private const val TAG = "EnvironmentService"
        private const val CHANNEL_ID = "androsid_linux"
        private const val NOTIF_ID = 2
        private val LISTENING_PORT = Regex("""listening on .+ port (\d+)""")

        const val ACTION_REPLACE_ROOTFS = "com.androsid.action.REPLACE_ROOTFS"
        const val ACTION_ADD_AUTHORIZED_KEY = "com.androsid.action.ADD_AUTHORIZED_KEY"
    }

    @Volatile
    private var supervisorThread: Thread? = null

    private val attempting = AtomicBoolean(false)
    private val notificationManager by lazy { getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager }

    override fun onCreate() {
        super.onCreate()
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "androsid Linux environment", NotificationManager.IMPORTANCE_LOW)
        )
        startForeground(NOTIF_ID, buildNotification("Starting..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val result = super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_REPLACE_ROOTFS -> Thread(::replaceAndRestart, "linux-env-replace").start()
            ACTION_ADD_AUTHORIZED_KEY -> Thread(::addAuthorizedKey, "linux-env-addkey").start()
            else -> tryStartEnvironment()
        }
        return result
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy: firing")
        supervisorThread?.interrupt()
        Thread(::killDescendantProcesses, "linux-env-teardown-sweep").start()
        super.onDestroy()
    }

    private fun tryStartEnvironment() {
        if (!attempting.compareAndSet(false, true)) {
            Log.i(TAG, "already running or attempting, ignoring extra start")
            return
        }
        supervisorThread = Thread({
            try {
                runEnvironment()
            } finally {
                attempting.set(false)
            }
        }, "linux-env-supervisor").apply { start() }
    }

    private fun replaceAndRestart() {
        Log.i(TAG, "replacing the environment with the freshly shared rootfs")

        val incoming = File(filesDir, "incoming/rootfs.tar")
        if (!incoming.exists()) {
            Log.w(TAG, "no incoming tarball to replace with")
            return
        }
        if (!isTarSafe(incoming)) {
            Log.e(TAG, "rejected tarball with entries escaping the target directory, keeping current environment")
            incoming.delete()
            return
        }

        stopCurrentAttempt()
        if (!deleteRootfs()) {
            Log.e(TAG, "rootfs delete left something behind, aborting replace")
            return
        }
        tryStartEnvironment()
    }

    private fun deleteRootfs(): Boolean {
        val rootfs = File(filesDir, "rootfs")
        if (!rootfs.exists()) return true
        return rootfs.deleteRecursively() && !rootfs.exists()
    }

    private fun addAuthorizedKey() {
        val incoming = File(filesDir, "incoming/authorized_key.pub")
        if (!incoming.exists()) {
            Log.w(TAG, "no incoming public key to authorize")
            return
        }
        val pubkey = incoming.readText().trim()
        incoming.delete()

        val rootfs = File(filesDir, "rootfs")
        if (!File(rootfs, "root").isDirectory) {
            Log.w(TAG, "no rootfs extracted yet, ignoring shared key")
            return
        }

        val sshDir = File(rootfs, "root/.ssh").apply { mkdirs() }
        Os.chmod(sshDir.absolutePath, "700".toInt(8))

        val authorizedKeys = File(sshDir, "authorized_keys")
        if (authorizedKeys.exists() && authorizedKeys.readLines().contains(pubkey)) {
            Log.i(TAG, "key already authorized, ignoring duplicate")
            return
        }

        authorizedKeys.appendText(pubkey + "\n")
        Os.chmod(authorizedKeys.absolutePath, "600".toInt(8))
        Log.i(TAG, "authorized new SSH public key")
    }

    private fun stopCurrentAttempt() {
        supervisorThread?.interrupt()
        killDescendantProcesses()
        supervisorThread?.join(5_000)
        attempting.set(false)
    }

    private fun killDescendantProcesses() {
        Log.i(TAG, "killDescendantProcesses: starting")
        val myUid = android.os.Process.myUid()
        val childrenOf = mutableMapOf<Int, MutableList<Int>>()
        File("/proc").listFiles()?.forEach { procDir ->
            val pid = procDir.name.toIntOrNull() ?: return@forEach
            val ownedByUs = runCatching { Os.stat(procDir.path).st_uid == myUid }.getOrDefault(false)
            if (!ownedByUs) return@forEach
            val ppid = runCatching {
                File(procDir, "status").useLines { lines ->
                    lines.firstOrNull { it.startsWith("PPid:") }
                        ?.removePrefix("PPid:")?.trim()?.toIntOrNull()
                }
            }.getOrNull() ?: return@forEach
            childrenOf.getOrPut(ppid) { mutableListOf() }.add(pid)
        }

        val toKill = mutableListOf<Int>()
        val queue = ArrayDeque<Int>().apply { add(android.os.Process.myPid()) }
        while (queue.isNotEmpty()) {
            val children = childrenOf[queue.removeFirst()].orEmpty()
            toKill += children
            queue += children
        }
        Log.i(TAG, "killDescendantProcesses: discovered $toKill")

        for (pid in toKill.asReversed()) {
            try {
                android.os.Process.sendSignal(pid, android.os.Process.SIGNAL_KILL)
                Log.i(TAG, "killDescendantProcesses: killed $pid")
            } catch (e: Exception) {
                Log.w(TAG, "couldn't kill descendant pid $pid", e)
            }
        }
        Log.i(TAG, "killDescendantProcesses: done")
    }

    private fun tryExtractRootfs(): File? {
        val rootfs = File(filesDir, "rootfs")

        if (rootfs.list()?.isNotEmpty() == true) return rootfs

        val incoming = File(filesDir, "incoming/rootfs.tar")
        if (!incoming.exists()) {
            Log.w(TAG, "no rootfs at ${rootfs.absolutePath} and no tarball at ${incoming.absolutePath}")
            return null
        }

        if (!isTarSafe(incoming)) {
            Log.e(TAG, "rejected tarball with entries escaping the target directory")
            incoming.delete()
            return null
        }

        Log.i(TAG, "extracting ${incoming.absolutePath} -> ${rootfs.absolutePath}")
        rootfs.deleteRecursively()
        rootfs.mkdirs()

        val pb = ProcessBuilder("/system/bin/tar", "xzf", incoming.absolutePath, "-C", rootfs.absolutePath)
        pb.redirectErrorStream(true)
        val process = pb.start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            Log.e(TAG, "extraction failed (tar exit $exitCode):\n$output")
            rootfs.deleteRecursively()
            incoming.delete()
            return null
        }

        Log.i(TAG, "rootfs extracted successfully")
        incoming.delete()
        return rootfs
    }

    private fun isTarSafe(tarball: File): Boolean {
        val pb = ProcessBuilder("/system/bin/tar", "tf", tarball.absolutePath)
        pb.redirectErrorStream(true)
        val process = pb.start()
        val entries = process.inputStream.bufferedReader().readLines()
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            Log.e(TAG, "failed to list tarball contents (exit $exitCode)")
            return false
        }

        val root = Paths.get("/rootfs")
        for (entry in entries) {
            val (name, symlinkTarget) = entry.split(" -> ", limit = 2).let { it[0] to it.getOrNull(1) }

            val resolvedEntry = root.resolve(name).normalize()
            if (name.startsWith("/") || !resolvedEntry.startsWith(root)) {
                Log.e(TAG, "tarball entry escapes target directory: $entry")
                return false
            }

            if (symlinkTarget != null && !symlinkTarget.startsWith("/") &&
                !resolvedEntry.resolveSibling(symlinkTarget).normalize().startsWith(root)) {
                Log.e(TAG, "tarball symlink escapes target directory: $entry")
                return false
            }
        }
        return true
    }

    private fun runEnvironment() {
        val rootfs = tryExtractRootfs()
        if (rootfs == null) {
            Log.w(TAG, "no rootfs available, nothing to launch")
            return
        }

        try {
            val process = ProotLauncher.launch(
                context = this,
                rootfs = rootfs,
                command = listOf("/entrypoint.sh"),
            )
            Log.i(TAG, "entrypoint starting")
            process.inputStream.bufferedReader().forEachLine { line ->
                Log.i(TAG, "entrypoint: $line")
                LISTENING_PORT.find(line)?.groupValues?.get(1)?.let { port ->
                    notify(buildNotification("SSH available on port $port"))
                }
            }
            Log.w(TAG, "entrypoint exited with code ${process.waitFor()}")
        } catch (e: Exception) {
            Log.e(TAG, "failed to launch the Linux environment", e)
        }
    }

    private fun notify(notification: Notification) {
        notificationManager.notify(NOTIF_ID, notification)
    }

    private fun buildNotification(statusText: String): Notification {
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("androsid")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setContentIntent(tap)
            .build()
    }
}
