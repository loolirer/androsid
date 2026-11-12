package com.androsid.sensors

import android.content.Context
import android.hardware.Sensor as HardwareSensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.util.Log
import com.androsid.bridge.StreamServer

class IMU(
    context: Context,
    server: StreamServer,
    private val handler: Handler,
) : Sensor<SensorEvent>(context, server) {

    companion object {
        private const val SENSOR_PERIOD_US = 5000
        private const val MAG_SCALE = 1e-6f // From uT to T

        private val TYPES = listOf(
            HardwareSensor.TYPE_ACCELEROMETER,
            HardwareSensor.TYPE_GYROSCOPE,
            HardwareSensor.TYPE_MAGNETIC_FIELD
        )
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var lastAccel: FloatArray? = null

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) = emit(event)
        override fun onAccuracyChanged(sensor: HardwareSensor?, accuracy: Int) {}
    }

    override fun onStart() {
        for (type in TYPES) {
            val sensor = sensorManager.getDefaultSensor(type)
            if (sensor == null) {
                Log.w(tag, "no sensor of type $type on this device")
                continue
            }
            sensorManager.registerListener(listener, sensor, SENSOR_PERIOD_US, handler)
        }
    }

    override fun onStop() {
        sensorManager.unregisterListener(listener)
    }

    override fun read(event: SensorEvent): ByteArray? = when (event.sensor.type) {
        HardwareSensor.TYPE_ACCELEROMETER -> {
            lastAccel = event.values.clone()
            null
        }
        HardwareSensor.TYPE_GYROSCOPE -> lastAccel?.let { accel ->
            message(
                "imu", event.timestamp,
                """"accel":[${accel[0]},${accel[1]},${accel[2]}],"gyro":[${event.values[0]},${event.values[1]},${event.values[2]}]"""
            )
        }
        HardwareSensor.TYPE_MAGNETIC_FIELD -> message(
            "mag", event.timestamp,
            """"mag":[${event.values[0] * MAG_SCALE},${event.values[1] * MAG_SCALE},${event.values[2] * MAG_SCALE}]"""
        )
        else -> null
    }
}
