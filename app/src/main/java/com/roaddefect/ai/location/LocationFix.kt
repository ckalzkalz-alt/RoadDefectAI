package com.roaddefect.ai.location

import com.roaddefect.ai.config.DetectionConfig
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Pure (Android-free) snapshot of a GPS fix so tracking and quality logic is unit-testable. */
data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Float,
    /** Metres per second, or null if the provider has no speed. */
    val speedMps: Float?,
    /** Wall-clock time of the fix (epoch ms). */
    val timeMs: Long,
)

enum class LocationQuality { GOOD, LOW, NONE }

object LocationAssessor {
    /**
     * NONE = no usable fix (never received, stale/lost, or invalid numbers);
     * LOW  = fix exists but accuracy is worse than the configured threshold;
     * GOOD = fresh fix within the accuracy threshold.
     * Low-accuracy fixes are still stored (flagged), never silently treated as good.
     */
    fun assess(fix: LocationFix?, nowMs: Long, config: DetectionConfig): LocationQuality {
        if (fix == null) return LocationQuality.NONE
        if (!fix.latitude.isFinite() || !fix.longitude.isFinite() ||
            fix.latitude !in -90.0..90.0 || fix.longitude !in -180.0..180.0
        ) return LocationQuality.NONE
        if (nowMs - fix.timeMs > config.gpsStaleMs) return LocationQuality.NONE
        if (!fix.accuracyM.isFinite() || fix.accuracyM <= 0f || fix.accuracyM > config.lowAccuracyThresholdM) {
            return LocationQuality.LOW
        }
        return LocationQuality.GOOD
    }
}

object Geo {
    private const val EARTH_RADIUS_M = 6_371_000.0

    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1 - a))
    }
}
