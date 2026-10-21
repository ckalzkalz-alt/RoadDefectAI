package com.roaddefect.ai.config

import android.content.Context

/** All tunables live here (never hard-coded elsewhere) so they can be adjusted after field testing. */
data class DetectionConfig(
    /** Minimum model confidence (0..1) for a detection to count toward a defect. */
    val minConfidence: Float = 0.70f,
    /** Frames analysed per second (the camera preview itself runs at full rate). */
    val inferenceFps: Int = 5,
    /** A track must be seen in at least this many analysed frames to become a record... */
    val minPersistFrames: Int = 3,
    /** ...or reach this confidence in a single frame (robust single-frame criterion). */
    val strongSingleFrameConfidence: Float = 0.92f,
    /** A track not seen for this long is closed and recorded (or dropped if never confirmed). */
    val trackTimeoutMs: Long = 1500L,
    /** Boxes of the same type with at least this IoU (after motion allowance) join the same track. */
    val trackMatchIou: Float = 0.15f,
    /** Same-type defects closer than this many metres to a recent record are suppressed... */
    val minDistanceBetweenSameTypeM: Float = 8f,
    /** ...if they occurred within this many ms (cooldown). */
    val cooldownMs: Long = 4000L,
    /** GPS accuracy worse than this (metres) marks the location as low accuracy. */
    val lowAccuracyThresholdM: Float = 20f,
    /** GPS fix older than this is treated as lost. */
    val gpsStaleMs: Long = 5000L,
    /** Saved defect image: longest side in px and JPEG quality. */
    val imageMaxSidePx: Int = 1280,
    val imageJpegQuality: Int = 80,
    /** Ignore fixes that imply impossible jumps / tiny jitter when summing distance. */
    val minSegmentForDistanceM: Float = 3f,
)

class ConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("roaddefect_config", Context.MODE_PRIVATE)

    fun load(): DetectionConfig {
        val d = DetectionConfig()
        return d.copy(
            minConfidence = prefs.getFloat("minConfidence", d.minConfidence),
            inferenceFps = prefs.getInt("inferenceFps", d.inferenceFps),
            minDistanceBetweenSameTypeM = prefs.getFloat("minDistance", d.minDistanceBetweenSameTypeM),
            cooldownMs = prefs.getLong("cooldownMs", d.cooldownMs),
        )
    }

    fun save(c: DetectionConfig) {
        prefs.edit()
            .putFloat("minConfidence", c.minConfidence)
            .putInt("inferenceFps", c.inferenceFps)
            .putFloat("minDistance", c.minDistanceBetweenSameTypeM)
            .putLong("cooldownMs", c.cooldownMs)
            .apply()
    }

    var forceMock: Boolean
        get() = prefs.getBoolean("forceMock", false)
        set(v) = prefs.edit().putBoolean("forceMock", v).apply()
}
