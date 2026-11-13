package com.androsid.sensors

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.SystemClock
import com.androsid.bridge.StreamServer

class Battery(
    context: Context,
    server: StreamServer,
    private val handler: Handler,
) : Sensor<Intent>(context, server) {

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = emit(intent)
    }

    override fun onStart() {
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), null, handler)
    }

    override fun onStop() {
        context.unregisterReceiver(receiver)
    }

    override fun read(event: Intent): ByteArray {
        val level = event.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = event.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percentage = if (level >= 0 && scale > 0) level / scale.toFloat() else Float.NaN

        val voltageMv = event.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
        val voltage = if (voltageMv != -1) voltageMv * 1e-3f else Float.NaN

        val tempTenths = event.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
        val temperature = if (tempTenths != -1) tempTenths * 1e-1f else Float.NaN

        val statusCode = event.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val status = when (statusCode) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
            BatteryManager.BATTERY_STATUS_FULL -> "full"
            else -> "unknown"
        }

        val healthCode = event.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)
        val health = when (healthCode) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
            BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "overvoltage"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "unspecified_failure"
            BatteryManager.BATTERY_HEALTH_COLD -> "cold"
            else -> "unknown"
        }

        val present = event.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true)
        val technologyRaw = event.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: ""
        val technology = if (technologyRaw.isBlank()) "unknown" else technologyRaw.lowercase()

        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

        val currentUa = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: 0
        val current = if (currentUa != Int.MIN_VALUE && currentUa != 0) currentUa * 1e-6f else Float.NaN

        return message(
            "battery", SystemClock.elapsedRealtimeNanos(),
            """"voltage":$voltage,"temperature":$temperature,"current":$current,"percentage":$percentage,"status":"$status","health":"$health","present":$present,"technology":"$technology""""
        )
    }
}
