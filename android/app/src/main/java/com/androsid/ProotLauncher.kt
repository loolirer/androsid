package com.androsid

import android.content.Context
import android.system.Os
import java.io.File

object ProotLauncher {

    fun launch(
        context: Context,
        rootfs: File,
        command: List<String>,
        workingDir: String = "/root",
    ): Process {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val libDir = File(context.filesDir, "proot-lib").apply { mkdirs() }
        val tmpDir = File(context.filesDir, "proot-tmp").apply { mkdirs() }
        val shmDir = File(context.filesDir, "rootfs-shm").apply { mkdirs() }
        Os.chmod(shmDir.absolutePath, "700".toInt(8))

        val talloc = File(libDir, "libtalloc.so.2")
        if (!talloc.exists()) File(nativeDir, "libtalloc.so").copyTo(talloc)
        val shmem = File(libDir, "libandroid-shmem.so")
        if (!shmem.exists()) File(nativeDir, "libandroid-shmem.so").copyTo(shmem)

        val proot = File(nativeDir, "libproot.so").absolutePath
        val loader = File(nativeDir, "libproot_loader.so").absolutePath

        val argv = listOf(
            proot,
            "--kill-on-exit", "--link2symlink", "--sysvipc", "-L",
            "-0",
            "-r", rootfs.absolutePath,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", "${shmDir.absolutePath}:/dev/shm",
            "-b", "/dev/urandom:/dev/random",
            "-w", workingDir,
        ) + command

        val pb = ProcessBuilder(argv)
        pb.environment()["LD_LIBRARY_PATH"] = libDir.absolutePath
        pb.environment()["PROOT_LOADER"] = loader
        pb.environment()["PROOT_TMP_DIR"] = tmpDir.absolutePath
        pb.environment()["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        pb.environment()["HOME"] = workingDir
        pb.redirectErrorStream(true)
        return pb.start()
    }
}
