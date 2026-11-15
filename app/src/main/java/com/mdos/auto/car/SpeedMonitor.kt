package com.mdos.auto.car

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.hardware.CarHardwareManager
import androidx.car.app.hardware.common.CarValue
import androidx.car.app.hardware.common.OnCarDataAvailableListener
import androidx.car.app.hardware.info.CarInfo
import androidx.car.app.hardware.info.Speed
import androidx.core.content.ContextCompat
import kotlin.math.abs

enum class DriveState { PARKED, DRIVING, UNKNOWN }

/**
 * Works out whether the car is parked. Uses the car's own speed sensor (via Android Auto), and the
 * phone's GPS as a fallback. With no speed data at all the state is UNKNOWN, which keeps video hidden.
 */
class SpeedMonitor(
    private val carContext: CarContext,
    private val onChange: (DriveState) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var carSpeed: Float? = null
    private var gpsSpeed: Float? = null
    private var gpsTime = 0L
    private var slowSince = 0L
    private var carInfo: CarInfo? = null
    private var locationManager: LocationManager? = null

    var state = DriveState.UNKNOWN
        private set

    private val carListener = OnCarDataAvailableListener<Speed> { s ->
        val v = s.rawSpeedMetersPerSecond
        if (v.status == CarValue.STATUS_SUCCESS) v.value?.let { carSpeed = abs(it) }
    }

    private val locListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (location.hasSpeed()) {
                gpsSpeed = location.speed
                gpsTime = SystemClock.elapsedRealtime()
            }
        }

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private val tick = object : Runnable {
        override fun run() {
            evaluate()
            handler.postDelayed(this, 1000)
        }
    }

    fun start() {
        try {
            val info = carContext.getCarService(CarHardwareManager::class.java).carInfo
            info.addSpeedListener(ContextCompat.getMainExecutor(carContext), carListener)
            carInfo = info
        } catch (t: Throwable) {
            Log.w(TAG, "Car speed not available", t)
        }
        val granted = ContextCompat.checkSelfPermission(carContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            try {
                val lm = carContext.getSystemService(LocationManager::class.java)
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locListener, Looper.getMainLooper())
                locationManager = lm
            } catch (t: Throwable) {
                Log.w(TAG, "GPS not available", t)
            }
        }
        handler.post(tick)
    }

    fun stop() {
        handler.removeCallbacks(tick)
        try { carInfo?.removeSpeedListener(carListener) } catch (_: Throwable) {}
        try { locationManager?.removeUpdates(locListener) } catch (_: Throwable) {}
    }

    private fun evaluate() {
        val now = SystemClock.elapsedRealtime()
        val speed = carSpeed ?: gpsSpeed?.takeIf { now - gpsTime < 10_000 }
        val next = when {
            speed == null -> DriveState.UNKNOWN
            speed > MOVING_MPS -> {
                slowSince = 0
                DriveState.DRIVING
            }
            speed < STOPPED_MPS -> {
                if (state != DriveState.DRIVING) {
                    DriveState.PARKED
                } else {
                    if (slowSince == 0L) slowSince = now
                    if (now - slowSince >= 3000) DriveState.PARKED else DriveState.DRIVING
                }
            }
            else -> {
                slowSince = 0
                if (state == DriveState.PARKED) DriveState.PARKED else DriveState.DRIVING
            }
        }
        if (next != state) {
            state = next
            onChange(next)
        }
    }

    private companion object {
        const val TAG = "MdOsSpeed"
        const val MOVING_MPS = 2.5f   // ~9 km/h
        const val STOPPED_MPS = 1.0f  // ~3.6 km/h, held for 3 s
    }
}
