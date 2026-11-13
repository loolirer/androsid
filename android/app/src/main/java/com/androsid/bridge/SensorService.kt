package com.androsid.bridge

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.util.Log
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
import org.json.JSONObject
import java.io.File

class SensorService : ForegroundService(
    channelId = "androsid_stream",
    channelName = "androsid stream",
    notificationId = 1,
    initialText = "Streaming sensors",
) {

    companion object {
        private const val TAG = "SensorService"
    }

    private lateinit var server: StreamServer
    private var sensors: List<Sensor<*>> = emptyList()
    @Volatile private var actuators: Map<String, Actuator> = emptyMap()
    private var wakeLock: PowerManager.WakeLock? = null
    private var sensorThread: HandlerThread? = null

    override fun onCreate() {
        super.onCreate()

        server = StreamServer(File(filesDir, "${ProotLauncher.SOCKET_DIR}/mobile_sensors.sock")) {
            rawCommand -> handleCommand(rawCommand)
        }
        server.start()

        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "androsid::stream")
            .apply { acquire() }

        val thread = HandlerThread("androsid-sensors").also { it.start() }
        sensorThread = thread
        val handler = Handler(thread.looper)

        val camera = Camera(this, server, this)
        sensors = listOf(
            IMU(this, server, handler),
            Magnetometer(this, server, handler),
            GPS(this, server, handler),
            camera,
            Battery(this, server, handler),
        )
        actuators = listOf(Torch(camera::boundCameras)).associateBy { it.command }

        sensors.forEach { it.start() }
    }

    override fun onDestroy() {
        sensors.forEach { it.stop() }
        sensorThread?.quitSafely()
        sensorThread = null
        wakeLock?.takeIf { it.isHeld }?.release()
        server.stop()
        super.onDestroy()
    }

    private fun handleCommand(cmdJson: String) {
        try {
            val json = JSONObject(cmdJson)
            val cmd = json.optString("cmd", "")

            val actuator = actuators[cmd]
            if (actuator != null) {
                actuator.handle(json)
            } else {
                Log.w(TAG, "Unknown or unhandled command received: $cmd")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling incoming command", e)
        }
    }
}
