package com.androsid.sensors

import android.content.Context
import android.hardware.Sensor as HardwareSensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.util.Log
import com.androsid.bridge.StreamServer

class Magnetometer(
    context: Context,
    server: StreamServer,
    private val handler: Handler,
) : Sensor<SensorEvent>(context, server) {

    companion object {
        private const val SENSOR_PERIOD_US = 10000
        private const val MAG_SCALE = 1e-6f // From uT to T
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) = emit(event)
        override fun onAccuracyChanged(sensor: HardwareSensor?, accuracy: Int) {}
    }

    override fun onStart() {
        val sensor = sensorManager.getDefaultSensor(HardwareSensor.TYPE_MAGNETIC_FIELD)
        if (sensor == null) {
            Log.w(tag, "no magnetometer on this device")
            return
        }
        sensorManager.registerListener(listener, sensor, SENSOR_PERIOD_US, handler)
    }

    override fun onStop() {
        sensorManager.unregisterListener(listener)
    }

    override fun read(event: SensorEvent): ByteArray = message(
        "mag", event.timestamp,
        """"mag":[${event.values[0] * MAG_SCALE},${event.values[1] * MAG_SCALE},${event.values[2] * MAG_SCALE}]"""
    )
}
