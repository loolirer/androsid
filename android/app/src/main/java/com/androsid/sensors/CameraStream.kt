package com.androsid.sensors

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Base64
import android.util.Log
import androidx.camera.core.Camera as BoundCamera
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraStream(
    context: Context,
    socket: File,
    private val name: String,
) : Sensor<ImageProxy>(context, socket) {

    companion object {
        private const val JPEG_QUALITY = 70

        private val FRAME_SUFFIX = "\"}\n".toByteArray(Charsets.US_ASCII)
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var loggedRotation = false

    val imageAnalysis: ImageAnalysis = ImageAnalysis.Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .build()
        .also { it.setAnalyzer(executor) { image -> handleFrame(image) } }

    @Volatile var camera: BoundCamera? = null

    override fun onStart() {}

    override fun onStop() {
        executor.shutdown()
    }

    override fun read(event: ImageProxy): ByteArray {
        val nv21 = event.toNv21()
        val yuv = YuvImage(nv21, ImageFormat.NV21, event.width, event.height, null)
        val jpeg = ByteArrayOutputStream(64 * 1024).use { buf ->
            yuv.compressToJpeg(Rect(0, 0, event.width, event.height), JPEG_QUALITY, buf)
            buf.toByteArray()
        }

        val prefix = (header(event.imageInfo.timestamp) + "\"data\":\"")
            .toByteArray(Charsets.US_ASCII)
        val b64 = Base64.encode(jpeg, Base64.NO_WRAP)

        return ByteArrayOutputStream(prefix.size + b64.size + FRAME_SUFFIX.size).apply {
            write(prefix)
            write(b64)
            write(FRAME_SUFFIX)
        }.toByteArray()
    }

    private fun handleFrame(image: ImageProxy) {
        try {
            if (!loggedRotation) {
                loggedRotation = true
                Log.i(tag, "camera '$name' first frame: ${image.width}x${image.height}, " +
                    "rotationDegrees=${image.imageInfo.rotationDegrees}")
            }

            emit(image)
        } catch (e: Exception) {
            Log.e(tag, "frame encode failed for camera '$name'", e)
        } finally {
            image.close()
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
