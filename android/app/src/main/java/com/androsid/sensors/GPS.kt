package com.androsid.sensors

import android.Manifest
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.util.Log
import com.androsid.bridge.StreamServer

class GPS(
    context: Context,
    server: StreamServer,
    private val handler: Handler,
) : Sensor<Location>(context, server) {

    companion object {
        private const val LOCATION_PERIOD_MS = 1000L
    }

    override val permission = Manifest.permission.ACCESS_FINE_LOCATION

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var loggedFirstFix = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(loc: Location) = emit(loc)

        @Deprecated("required by LocationListener on API < 29")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    override fun onStart() {
        // FUSED blends GNSS with Wi-Fi and cell trilateration for coarse estimation
        val provider = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            locationManager.allProviders.contains(LocationManager.FUSED_PROVIDER)
        ) LocationManager.FUSED_PROVIDER else LocationManager.GPS_PROVIDER

        try {
            locationManager.requestLocationUpdates(provider, LOCATION_PERIOD_MS, 0f, listener, handler.looper)
            Log.i(tag, "location updates requested from '$provider'")
        } catch (e: Exception) {
            Log.e(tag, "could not start location updates", e)
        }
    }

    override fun onStop() {
        locationManager.removeUpdates(listener)
    }

    override fun read(event: Location): ByteArray {
        val vertAcc = event.verticalAccuracyMeters.toDouble()

        if (!loggedFirstFix) {
            loggedFirstFix = true
            Log.i(tag, "first fix from '${event.provider}', accuracy ${event.accuracy} m")
        }

        return message(
            "gps", event.elapsedRealtimeNanos,
            """"latitude":${event.latitude},"longitude":${event.longitude},""" +
            """"altitude":${event.altitude},"accuracy":${event.accuracy},"vertical_accuracy":$vertAcc,""" +
            """"speed":${event.speed},"bearing":${event.bearing},""" +
            """"provider":"${event.provider}""""
        )
    }
}
