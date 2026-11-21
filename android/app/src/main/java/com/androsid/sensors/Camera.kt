package com.androsid.sensors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.util.Log
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera as BoundCamera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ConcurrentCamera
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File

// Binds the physical cameras together through CameraX and runs one CameraStream per camera
class Camera(
    private val context: Context,
    private val socketDir: File,
    private val lifecycleOwner: LifecycleOwner,
) {

    companion object {
        private const val TAG = "Camera"
    }

    private var started = false
    private var cameraProvider: ProcessCameraProvider? = null
    @Volatile private var streams: List<CameraStream> = emptyList()

    val boundCameras: List<BoundCamera>
        get() = streams.mapNotNull { it.camera }

    fun start() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "${Manifest.permission.CAMERA} not granted, $TAG disabled")
            return
        }
        started = true

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (!started) return@addListener

            try {
                val provider = future.get().also { cameraProvider = it }

                // CameraX supports binding at most 2 cameras concurrently
                // https://developer.android.com/reference/androidx/camera/core/ConcurrentCamera
                val combos = provider.availableConcurrentCameraInfos

                if (combos.isNotEmpty()) {
                    bindConcurrentCombo(provider, combos[0])
                } else {
                    Log.w(TAG, "device does not support concurrent camera streaming, " +
                        "falling back to a single camera")
                    bindSingleCamera(provider)
                }
            } catch (e: Exception) {
                Log.e(TAG, "failed to start cameras", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        if (!started) return
        started = false
        streams.forEach { it.stop() }
        streams = emptyList()
        cameraProvider?.unbindAll()
    }

    private fun newStream(name: String): CameraStream =
        CameraStream(context, File(socketDir, "camera_$name.sock"), name).also { it.start() }

    private fun bindConcurrentCombo(provider: ProcessCameraProvider, combo: List<CameraInfo>) {
        Log.i(TAG, "using first reported combo: ${combo.size} camera(s)")

        var frontCount = 0
        var rearCount = 0
        val names = combo.map { info ->
            when (lensFacingOf(info)) {
                CameraSelector.LENS_FACING_FRONT -> "front_${frontCount++}"
                CameraSelector.LENS_FACING_BACK -> "rear_${rearCount++}"

                // When hardware fails to report any of the cameras
                else -> "camera_0"
            }
        }

        val newStreams = names.map { newStream(it) }
        streams = newStreams

        val singleConfigs = combo.mapIndexed { index, info ->
            ConcurrentCamera.SingleCameraConfig(
                info.cameraSelector,
                UseCaseGroup.Builder().addUseCase(newStreams[index].imageAnalysis).build(),
                lifecycleOwner
            )
        }

        val concurrentCamera = provider.bindToLifecycle(singleConfigs)
        concurrentCamera.cameras.forEachIndexed { index, cam ->
            newStreams.getOrNull(index)?.camera = cam
        }
        Log.i(TAG, "cameras bound: $names")
    }

    private fun bindSingleCamera(provider: ProcessCameraProvider) {
        val backSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()
        val frontSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
            .build()

        val (selector, name) = when {
            provider.hasCamera(backSelector) -> backSelector to "rear_0"
            provider.hasCamera(frontSelector) -> frontSelector to "front_0"
            else -> {
                Log.w(TAG, "no usable camera found on this device, camera streaming disabled")
                return
            }
        }

        val stream = newStream(name)
        streams = listOf(stream)

        stream.camera = provider.bindToLifecycle(lifecycleOwner, selector, stream.imageAnalysis)
        Log.i(TAG, "camera bound: $name")
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun lensFacingOf(info: CameraInfo): Int? {
        return try {
            Camera2CameraInfo.from(info)
                .getCameraCharacteristic(CameraCharacteristics.LENS_FACING)
        } catch (e: Exception) {
            Log.w(TAG, "could not read lens facing", e)
            null
        }
    }
}
