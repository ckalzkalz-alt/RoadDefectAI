package com.roaddefect.ai.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Continuous GPS via the fused provider. Works offline (GPS needs no data connection). */
class LocationService(private val context: Context) : LocationSource {

    private val client = LocationServices.getFusedLocationProviderClient(context)
    private val _fix = MutableStateFlow<LocationFix?>(null)
    override val fix: StateFlow<LocationFix?> = _fix.asStateFlow()
    private var callback: LocationCallback? = null

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasFinePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * @return false when neither fine nor coarse location is granted (caller shows an error;
     * detection still works, defects are then saved flagged "no GPS"). With approximate-only
     * permission the fused provider runs on balanced power and the resulting positions are far
     * less accurate, which [com.roaddefect.ai.location.LocationAssessor] flags as LOW quality.
     */
    @SuppressLint("MissingPermission")
    override fun start(): Boolean {
        if (!hasPermission()) return false
        if (callback != null) return true
        val request = LocationRequest.Builder(
            if (hasFinePermission()) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            1000L,
        )
            .setMinUpdateIntervalMillis(500L)
            .build()
        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { _fix.value = it.toFix() }
            }
        }
        client.requestLocationUpdates(request, cb, Looper.getMainLooper())
        callback = cb
        return true
    }

    override fun stop() {
        callback?.let { client.removeLocationUpdates(it) }
        callback = null
        _fix.value = null
    }

    private fun Location.toFix() = LocationFix(
        latitude = latitude,
        longitude = longitude,
        accuracyM = if (hasAccuracy()) accuracy else Float.NaN,
        speedMps = if (hasSpeed()) speed else null,
        timeMs = System.currentTimeMillis(),
    )
}
