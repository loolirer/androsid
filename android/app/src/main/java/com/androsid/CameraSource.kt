package com.androsid

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Base64
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraSource(
    private val server: StreamServer,
    private val cameraName: String,
    private val jpegQuality: Int = 70
) {

    companion object {
        private const val TAG = "CameraSource"

        private val FRAME_MID = "\",\"t\":".toByteArray(Charsets.US_ASCII)
        private val FRAME_DATA = ",\"d\":\"".toByteArray(Charsets.US_ASCII)
        private val FRAME_SUFFIX = "\"}\n".toByteArray(Charsets.US_ASCII)
    }

    private val framePrefix =
        "{\"s\":\"frame\",\"c\":\"$cameraName".toByteArray(Charsets.US_ASCII)

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var loggedRotation = false

    val imageAnalysis: ImageAnalysis = ImageAnalysis.Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .build()
        .also { it.setAnalyzer(executor) { image -> handleFrame(image) } }

    fun stop() {
        executor.shutdown()
    }

    private fun handleFrame(image: ImageProxy) {
        try {
            if (!loggedRotation) {
                loggedRotation = true
                Log.i(TAG, "camera '$cameraName' first frame: ${image.width}x${image.height}, " +
                    "rotationDegrees=${image.imageInfo.rotationDegrees}")
            }

            if (!server.isConnected()) return

            val nv21 = image.toNv21()
            val yuv = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
            val jpeg = ByteArrayOutputStream(64 * 1024).use { buf ->
                yuv.compressToJpeg(Rect(0, 0, image.width, image.height), jpegQuality, buf)
                buf.toByteArray()
            }

            val stamp = image.imageInfo.timestamp + SensorService.bootToEpochNanos
            val stampBytes = stamp.toString().toByteArray(Charsets.US_ASCII)
            val b64 = Base64.encode(jpeg, Base64.NO_WRAP)

            val line = ByteArrayOutputStream(
                framePrefix.size + FRAME_MID.size + stampBytes.size +
                    FRAME_DATA.size + b64.size + FRAME_SUFFIX.size
            ).apply {
                write(framePrefix)
                write(FRAME_MID)
                write(stampBytes)
                write(FRAME_DATA)
                write(b64)
                write(FRAME_SUFFIX)
            }.toByteArray()

            server.broadcastLine(line)
        } catch (e: Exception) {
            Log.e(TAG, "frame encode failed for camera '$cameraName'", e)
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