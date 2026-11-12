package com.androsid.environment

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.system.Os
import android.util.Log
import com.androsid.ForegroundService
import java.io.File
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean

class EnvironmentService : ForegroundService(
    channelId = "androsid_linux",
    channelName = "androsid Linux environment",
    notificationId = 2,
    initialText = "Starting...",
) {

    companion object {
        private const val TAG = "EnvironmentService"
        private val LISTENING_PORT = Regex("""listening on .+ port (\d+)""")

        const val ACTION_REPLACE_ROOTFS = "com.androsid.action.REPLACE_ROOTFS"
        const val ACTION_ADD_AUTHORIZED_KEY = "com.androsid.action.ADD_AUTHORIZED_KEY"

        const val INCOMING_DIR = "incoming"
        const val INCOMING_ROOTFS = "rootfs.tar"
        const val INCOMING_AUTHORIZED_KEY = "authorized_key.pub"

        private const val VERIFIED_ROOTFS = "rootfs.verified.tar"
        private const val ROOTFS_DIR = "rootfs"
    }

    @Volatile
    private var supervisorThread: Thread? = null

    private val attempting = AtomicBoolean(false)
    private var multicastLock: WifiManager.MulticastLock? = null

    private val rootfsDir: File get() = File(filesDir, ROOTFS_DIR)
    private val incomingDir: File get() = File(filesDir, INCOMING_DIR)

    override fun onCreate() {
        super.onCreate()

        // The Wi-Fi firmware drops multicast for groups nothing has joined
        multicastLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createMulticastLock("androsid::dds")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
        Log.i(TAG, "multicast lock acquired, DDS discovery should reach this device")
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
        multicastLock?.takeIf { it.isHeld }?.release()
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

        if (verifiedTarball() == null) {
            Log.w(TAG, "no usable tarball to replace with, keeping current environment")
            return
        }

        stopCurrentAttempt()
        if (!deleteRootfs()) {
            Log.e(TAG, "rootfs delete left something behind, aborting replace")
            return
        }
        tryStartEnvironment()
    }

    // Each shared tarball is checked once, then renamed so later steps never re-check it
    private fun verifiedTarball(): File? {
        val incoming = File(incomingDir, INCOMING_ROOTFS)
        val verified = File(incomingDir, VERIFIED_ROOTFS)

        if (incoming.exists()) {
            if (!isTarSafe(incoming)) {
                Log.e(TAG, "rejected tarball with entries escaping the target directory")
                incoming.delete()
                return null
            }
            if (!incoming.renameTo(verified)) {
                Log.e(TAG, "could not move ${incoming.absolutePath} to ${verified.absolutePath}")
                return null
            }
        }

        if (!verified.exists()) {
            Log.w(TAG, "no tarball at ${incoming.absolutePath}")
            return null
        }
        return verified
    }

    private fun deleteRootfs(): Boolean {
        val rootfs = rootfsDir
        if (!rootfs.exists()) return true
        return rootfs.deleteRecursively() && !rootfs.exists()
    }

    private fun addAuthorizedKey() {
        val incoming = File(incomingDir, INCOMING_AUTHORIZED_KEY)
        if (!incoming.exists()) {
            Log.w(TAG, "no incoming public key to authorize")
            return
        }
        val pubkey = incoming.readText().trim()
        incoming.delete()

        val rootfs = rootfsDir
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
        val rootfs = rootfsDir

        if (rootfs.list()?.isNotEmpty() == true) return rootfs

        val tarball = verifiedTarball() ?: return null

        Log.i(TAG, "extracting ${tarball.absolutePath} -> ${rootfs.absolutePath}")
        rootfs.deleteRecursively()
        rootfs.mkdirs()

        val pb = ProcessBuilder("/system/bin/tar", "xzf", tarball.absolutePath, "-C", rootfs.absolutePath)
        pb.redirectErrorStream(true)
        val process = pb.start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            Log.e(TAG, "extraction failed (tar exit $exitCode):\n$output")
            rootfs.deleteRecursively()
            tarball.delete()
            return null
        }

        Log.i(TAG, "rootfs extracted successfully")
        tarball.delete()
        return rootfs
    }

    private fun isTarSafe(tarball: File): Boolean {
        val pb = ProcessBuilder("/system/bin/tar", "tvf", tarball.absolutePath)
        pb.redirectErrorStream(true)
        val process = pb.start()
        val entries = process.inputStream.bufferedReader().readLines()
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            Log.e(TAG, "failed to list tarball contents (exit $exitCode)")
            return false
        }

        val listingLine = Regex("""^(\S+)\s+\S+\s+\d+\s+\S+\s+\S+\s+(.*)$""")
        val root = Paths.get("/rootfs")
        for (entry in entries) {
            val match = listingLine.matchEntire(entry)
            if (match == null) {
                Log.e(TAG, "unrecognized tar listing line: $entry")
                return false
            }
            val (mode, rest) = match.destructured
            val (name, symlinkTarget) = if (mode.firstOrNull() == 'l') {
                rest.split(" -> ", limit = 2).let { it[0] to it.getOrNull(1) }
            } else {
                rest to null
            }

            val resolvedEntry = root.resolve(name).normalize()
            if (name.startsWith("/") || !resolvedEntry.startsWith(root)) {
                Log.e(TAG, "tarball entry escapes target directory: $entry")
                return false
            }

            if (symlinkTarget != null) {
                val resolvedTarget = if (symlinkTarget.startsWith("/")) {
                    root.resolve(symlinkTarget.trimStart('/'))
                } else {
                    resolvedEntry.resolveSibling(symlinkTarget)
                }.normalize()
                if (!resolvedTarget.startsWith(root)) {
                    Log.e(TAG, "tarball symlink escapes target directory: $entry")
                    return false
                }
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
                    updateNotification("SSH available on port $port")
                }
            }
            Log.w(TAG, "entrypoint exited with code ${process.waitFor()}")
        } catch (e: Exception) {
            Log.e(TAG, "failed to launch the Linux environment", e)
        }
    }
}
