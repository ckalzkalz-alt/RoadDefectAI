package com.roaddefect.ai.location

import com.roaddefect.ai.config.DetectionConfig
import kotlin.math.max

/**
 * Sums route distance from GPS fixes, ignoring unreliable fixes (poor accuracy), jitter while
 * stationary, and impossible jumps.
 */
class DistanceAccumulator(private val config: () -> DetectionConfig) {
    var totalM: Double = 0.0
        private set
    private var last: LocationFix? = null

    /** Forget the previous point (call on resume so the paused gap isn't counted as driven distance). */
    fun breakSegment() { last = null }

    fun onFix(fix: LocationFix): Double {
        val cfg = config()
        if (!fix.accuracyM.isFinite() || fix.accuracyM > cfg.lowAccuracyThresholdM) return totalM
        val prev = last
        if (prev == null) { last = fix; return totalM }

        val dtSec = (fix.timeMs - prev.timeMs) / 1000.0
        if (dtSec <= 0) return totalM
        val d = Geo.distanceM(prev.latitude, prev.longitude, fix.latitude, fix.longitude)
        if (d < max(cfg.minSegmentForDistanceM.toDouble(), fix.accuracyM * 0.5)) return totalM // jitter
        if (d / dtSec > MAX_PLAUSIBLE_MPS) { last = fix; return totalM } // GPS jump
        totalM += d
        last = fix
        return totalM
    }

    private companion object { const val MAX_PLAUSIBLE_MPS = 70.0 }
}
