package com.androsid.sensors

import android.Manifest
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCharacteristics
import android.util.Base64
import android.util.Log
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera as BoundCamera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ConcurrentCamera
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.androsid.bridge.StreamServer
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraFrame(val cameraName: String, val image: ImageProxy)

class Camera(
    context: Context,
    server: StreamServer,
    private val lifecycleOwner: LifecycleOwner,
) : Sensor<CameraFrame>(context, server) {

    companion object {
        private const val JPEG_QUALITY = 70

        private val FRAME_SUFFIX = "\"}\n".toByteArray(Charsets.US_ASCII)
    }

    override val permission = Manifest.permission.CAMERA

    private var cameraProvider: ProcessCameraProvider? = null
    @Volatile private var streams: List<Stream> = emptyList()

    override fun onStart() {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (!isStarted) return@addListener

            try {
                val provider = future.get().also { cameraProvider = it }

                // CameraX supports binding at most 2 cameras concurrently
                // https://developer.android.com/reference/androidx/camera/core/ConcurrentCamera
                val combos = provider.availableConcurrentCameraInfos

                if (combos.isNotEmpty()) {
                    bindConcurrentCombo(provider, combos[0])
                } else {
                    Log.w(tag, "device does not support concurrent camera streaming, " +
                        "falling back to a single camera")
                    bindSingleCamera(provider)
                }
            } catch (e: Exception) {
                Log.e(tag, "failed to start cameras", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    override fun onStop() {
        streams.forEach { it.stop() }
        streams = emptyList()
        cameraProvider?.unbindAll()
    }

    val boundCameras: List<BoundCamera>
        get() = streams.mapNotNull { it.camera }

    override fun read(event: CameraFrame): ByteArray {
        val image = event.image
        val nv21 = image.toNv21()
        val yuv = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val jpeg = ByteArrayOutputStream(64 * 1024).use { buf ->
            yuv.compressToJpeg(Rect(0, 0, image.width, image.height), JPEG_QUALITY, buf)
            buf.toByteArray()
        }

        val prefix = (header("frame", image.imageInfo.timestamp) +
            "\"camera_name\":\"${event.cameraName}\",\"data\":\"").toByteArray(Charsets.US_ASCII)
        val b64 = Base64.encode(jpeg, Base64.NO_WRAP)

        return ByteArrayOutputStream(prefix.size + b64.size + FRAME_SUFFIX.size).apply {
            write(prefix)
            write(b64)
            write(FRAME_SUFFIX)
        }.toByteArray()
    }

    private fun bindConcurrentCombo(provider: ProcessCameraProvider, combo: List<CameraInfo>) {
        Log.i(tag, "using first reported combo: ${combo.size} camera(s)")

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

        val newStreams = names.map { Stream(it) }
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
        Log.i(tag, "cameras bound: $names")
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
                Log.w(tag, "no usable camera found on this device, camera streaming disabled")
                return
            }
        }

        val stream = Stream(name)
        streams = listOf(stream)

        stream.camera = provider.bindToLifecycle(lifecycleOwner, selector, stream.imageAnalysis)
        Log.i(tag, "camera bound: $name")
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun lensFacingOf(info: CameraInfo): Int? {
        return try {
            Camera2CameraInfo.from(info)
                .getCameraCharacteristic(CameraCharacteristics.LENS_FACING)
        } catch (e: Exception) {
            Log.w(tag, "could not read lens facing", e)
            null
        }
    }

    private inner class Stream(private val name: String) {

        private val executor: ExecutorService = Executors.newSingleThreadExecutor()
        private var loggedRotation = false

        val imageAnalysis: ImageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { it.setAnalyzer(executor) { image -> handleFrame(image) } }

        @Volatile var camera: BoundCamera? = null

        fun stop() {
            executor.shutdown()
        }

        private fun handleFrame(image: ImageProxy) {
            try {
                if (!loggedRotation) {
                    loggedRotation = true
                    Log.i(tag, "camera '$name' first frame: ${image.width}x${image.height}, " +
                        "rotationDegrees=${image.imageInfo.rotationDegrees}")
                }

                emit(CameraFrame(name, image))
            } catch (e: Exception) {
                Log.e(tag, "frame encode failed for camera '$name'", e)
            } finally {
                image.close()
            }
        }
    }
}

private fun ImageProxy.toNv21(): ByteArray {
    val ySize = width * height
    val out = ByteArray(ySize + ySize / 2)

    val yBuf = planes[0].buffer.also { it.rewind() }
    val yRowStride = planes[0].rowStride
    var pos = 0
    if (yRowStride == width) {
        yBuf.get(out, 0, ySize)
        pos = ySize
    } else {
        for (row in 0 until height) {
            yBuf.position(row * yRowStride)
            yBuf.get(out, pos, width)
            pos += width
        }
    }

    val uBuf = planes[1].buffer.also { it.rewind() }
    val vBuf = planes[2].buffer.also { it.rewind() }
    val uvRowStride = planes[1].rowStride
    val uvPixelStride = planes[1].pixelStride

    for (row in 0 until height / 2) {
        for (col in 0 until width / 2) {
            val idx = row * uvRowStride + col * uvPixelStride
            out[pos++] = vBuf.get(idx)
            out[pos++] = uBuf.get(idx)
        }
    }
    return out
}
