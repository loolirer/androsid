package com.androsid

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService

import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.os.BatteryManager

class SensorService : LifecycleService(), SensorEventListener, LocationListener {

    companion object {
        const val PORT = 9870
        private const val TAG = "SensorService"
        private const val CHANNEL_ID = "androsid_stream"
        private const val NOTIF_ID = 1

        private const val SENSOR_PERIOD_US = 5000

        private const val LOCATION_PERIOD_MS = 1000L

        val bootToEpochNanos: Long =
            System.currentTimeMillis() * 1_000_000L - SystemClock.elapsedRealtimeNanos()
    }

    private lateinit var server: StreamServer
    private lateinit var sensorManager: SensorManager
    private var camera: CameraSource? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var sensorThread: HandlerThread? = null
    private var loggedFirstFix = false

    private var batteryReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()

        server = StreamServer(PORT) {
            rawCommand -> handleCommand(rawCommand)
        }
        server.start()

        startForeground(NOTIF_ID, buildNotification())

        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "androsid::stream")
            .apply { acquire() }

        // The Wi-Fi firmware drops multicast for groups nothing has joined
        multicastLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createMulticastLock("androsid::dds")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
        Log.i(TAG, "multicast lock acquired, DDS discovery should reach this device")

        val thread = HandlerThread("androsid-sensors").also { it.start() }
        sensorThread = thread

        startImu(Handler(thread.looper))
        startGps(thread.looper)
        startCamera()
        startBattery(Handler(thread.looper))
    }

    override fun onDestroy() {
        sensorManager.unregisterListener(this)
        camera?.stop()
        try {
            (getSystemService(Context.LOCATION_SERVICE) as LocationManager)
                .removeUpdates(this)
        } catch (_: SecurityException) {}

        batteryReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
            batteryReceiver = null
        }
        sensorThread?.quitSafely()
        sensorThread = null
        wakeLock?.takeIf { it.isHeld }?.release()
        multicastLock?.takeIf { it.isHeld }?.release()
        server.stop()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- sources

    private fun startImu(handler: Handler) {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val types = listOf(
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_MAGNETIC_FIELD
        )
        for (type in types) {
            val sensor = sensorManager.getDefaultSensor(type)
            if (sensor == null) {
                Log.w(TAG, "no sensor of type $type on this device")
                continue
            }
            sensorManager.registerListener(this, sensor, SENSOR_PERIOD_US, handler)
        }
    }

    private fun startGps(looper: Looper) {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            Log.w(TAG, "location permission not granted, GPS disabled")
            return
        }
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // FUSED blends GNSS with Wi-Fi and cell trilateration for coarse estimation
        val provider = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            lm.allProviders.contains(LocationManager.FUSED_PROVIDER)
        ) LocationManager.FUSED_PROVIDER else LocationManager.GPS_PROVIDER

        try {
            lm.requestLocationUpdates(provider, LOCATION_PERIOD_MS, 0f, this, looper)
            Log.i(TAG, "location updates requested from '$provider'")
        } catch (e: Exception) {
            Log.e(TAG, "could not start location updates", e)
        }
    }

    private fun startCamera() {
        if (!hasPermission(Manifest.permission.CAMERA)) {
            Log.w(TAG, "camera permission not granted, camera disabled")
            return
        }
        camera = CameraSource(this, this, server).also { it.start() }
    }

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    
    private fun startBattery(handler: Handler) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val percentage = if (level >= 0 && scale > 0) level / scale.toFloat() else Float.NaN

                val voltageMv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
                val voltage = if (voltageMv != -1) voltageMv / 1000.0f else Float.NaN

                val tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
                val temperature = if (tempTenths != -1) tempTenths / 10.0f else Float.NaN

                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
                val health = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)
                val present = intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true)
                val tech = intent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: ""

                val bm = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

                val currentUa = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: 0
                val current = if (currentUa != Int.MIN_VALUE && currentUa != 0) currentUa / 1_000_000.0f else Float.NaN

                val t = SystemClock.elapsedRealtimeNanos() + bootToEpochNanos

                server.broadcast(
                    """{"s":"battery","t":$t,"voltage":$voltage,"temperature":$temperature,"current":$current,"percentage":$percentage,"status":$status,"health":$health,"present":$present,"tech":"$tech"}"""
                )
            }
        }
        batteryReceiver = receiver
        registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), null, handler)
    }

    // -------------------------------------------------------------- callbacks

    override fun onSensorChanged(event: SensorEvent) {
        val name = when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> "accel"
            Sensor.TYPE_GYROSCOPE     -> "gyro"
            Sensor.TYPE_MAGNETIC_FIELD -> "mag"
            else -> return
        }
        val t = event.timestamp + bootToEpochNanos
        server.broadcast(
            """{"s":"$name","t":$t,"v":[${event.values[0]},${event.values[1]},${event.values[2]}]}"""
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onLocationChanged(loc: Location) {
        val t = loc.elapsedRealtimeNanos + bootToEpochNanos
        val vertAcc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            loc.verticalAccuracyMeters.toDouble() else 0.0

        if (!loggedFirstFix) {
            loggedFirstFix = true
            Log.i(TAG, "first fix from '${loc.provider}', accuracy ${loc.accuracy} m")
        }

        server.broadcast(
            """{"s":"gps","t":$t,"lat":${loc.latitude},"lon":${loc.longitude},""" +
            """"alt":${loc.altitude},"acc":${loc.accuracy},"vacc":$vertAcc,""" +
            """"speed":${loc.speed},"bearing":${loc.bearing},""" +
            """"prov":"${loc.provider}"}"""
        )
    }

    @Deprecated("required by LocationListener on API < 29")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    // ----------------------------------------------------------- notification

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, "androsid stream", NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("androsid")
            .setContentText("Streaming sensors on port $PORT")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setContentIntent(tap)
            .build()
    }
    
    // ----------------------------------------------------------- commands

    @Synchronized
    private fun handleCommand(cmdJson: String) {
        try {
            val json = org.json.JSONObject(cmdJson)
            val action = json.optString("action", "")

            when (action) {

                else -> {
                    Log.w(TAG, "Unknown or unhandled action received: $action")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling incoming command", e)
        }
    }
}
