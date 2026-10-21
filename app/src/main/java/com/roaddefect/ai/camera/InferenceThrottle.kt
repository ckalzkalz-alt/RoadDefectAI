package com.roaddefect.ai.camera

/**
 * Decides whether a camera frame is worth analysing, so inference runs at the configured rate
 * instead of on every frame. Pure Kotlin (no Android types) so the gate is unit-testable.
 *
 * Not thread-safe by design: it is called only from the single CameraX analysis thread.
 */
class InferenceThrottle(private val fps: () -> Int) {

    private var lastAcceptedMs: Long? = null

    fun shouldAnalyse(nowMs: Long): Boolean {
        val interval = 1000L / fps().coerceIn(1, 30)
        val last = lastAcceptedMs
        if (last != null && nowMs - last < interval) return false
        lastAcceptedMs = nowMs
        return true
    }

    /** Clears the window so the first frame of a new inspection is analysed immediately. */
    fun reset() {
        lastAcceptedMs = null
    }
}
