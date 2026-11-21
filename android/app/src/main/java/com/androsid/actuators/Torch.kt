package com.androsid.actuators

import android.util.Log
import androidx.camera.core.Camera
import org.json.JSONObject
import java.io.File

class Torch(
    socket: File,
    private val cameras: () -> List<Camera>,
) : Actuator(socket) {

    override fun handle(args: JSONObject) {
        val enabled = args.getBoolean("enabled")
        val target = cameras().firstOrNull { it.cameraInfo.hasFlashUnit() }
        if (target != null) {
            target.cameraControl.enableTorch(enabled)
            Log.i(tag, "Torch state set to: $enabled")
        } else {
            Log.w(tag, "No camera with flash unit available")
        }
    }
}
