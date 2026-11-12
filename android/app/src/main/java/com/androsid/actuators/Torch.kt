package com.androsid.actuators

import android.util.Log
import com.androsid.sensors.Camera
import org.json.JSONObject

class Torch(private val camera: Camera) : Actuator {

    companion object {
        private const val TAG = "Torch"
    }

    override val command = "torch"

    override fun handle(args: JSONObject) {
        val enabled = args.optBoolean("enabled", false)
        val target = camera.boundCameras.firstOrNull { it.cameraInfo.hasFlashUnit() }
        if (target != null) {
            target.cameraControl.enableTorch(enabled)
            Log.i(TAG, "Torch state set to: $enabled")
        } else {
            Log.w(TAG, "No camera with flash unit available")
        }
    }
}
