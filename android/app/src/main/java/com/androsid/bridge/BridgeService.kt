package com.androsid.bridge

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import com.androsid.ForegroundService
import com.androsid.actuators.Actuator
import com.androsid.actuators.Torch
import com.androsid.environment.ProotLauncher
import com.androsid.sensors.Battery
import com.androsid.sensors.Camera
import com.androsid.sensors.GPS
import com.androsid.sensors.IMU
import com.androsid.sensors.Magnetometer
import com.androsid.sensors.Sensor
import java.io.File

class BridgeService : ForegroundService(
    channelId = "androsid_stream",
    channelName = "androsid stream",
    notificationId = 1,
    initialText = "Androsid bridge active",
) {

    private var sensors: List<Sensor<*>> = emptyList()
    private var camera: Camera? = null
    private var actuators: List<Actuator> = emptyList()
    private var wakeLock: PowerManager.WakeLock? = null
    private var sensorThread: HandlerThread? = null

    private val socketDir by lazy { File(filesDir, ProotLauncher.SOCKET_DIR) }

    private fun socket(name: String) = File(socketDir, "$name.sock")

    override fun onCreate() {
        super.onCreate()

        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "androsid::stream")
            .apply { acquire() }

        val thread = HandlerThread("androsid-sensors").also { it.start() }
        sensorThread = thread
        val handler = Handler(thread.looper)

        val camera = Camera(this, socketDir, this).also { this.camera = it }
        sensors = listOf(
            IMU(this, socket("imu"), handler),
            Magnetometer(this, socket("mag"), handler),
            GPS(this, socket("gps"), handler),
            Battery(this, socket("battery"), handler),
        )
        actuators = listOf(
            Torch(socket("torch"), camera::boundCameras),
        )

        sensors.forEach { it.start() }
        camera.start()
        actuators.forEach { it.start() }
    }

    override fun onDestroy() {
        actuators.forEach { it.stop() }
        camera?.stop()
        sensors.forEach { it.stop() }
        sensorThread?.quitSafely()
        sensorThread = null
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }
}
