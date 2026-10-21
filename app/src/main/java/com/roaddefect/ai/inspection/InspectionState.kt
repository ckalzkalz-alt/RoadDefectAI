package com.roaddefect.ai.inspection

import com.roaddefect.ai.ai.BoundingBox
import com.roaddefect.ai.ai.DefectType
import com.roaddefect.ai.location.LocationQuality

enum class Phase { IDLE, STARTING, ACTIVE, PAUSED, STOPPING }

/** One box drawn over the camera preview. [recorded] is true on the frame that created a record. */
data class OverlayBox(
    val type: DefectType,
    val confidence: Float,
    val box: BoundingBox,
    val recorded: Boolean,
)

data class InspectionUiState(
    val phase: Phase = Phase.IDLE,
    val inspectionId: Long? = null,
    val elapsedMs: Long = 0,
    val distanceM: Double = 0.0,
    val speedMps: Float? = null,
    val gpsQuality: LocationQuality = LocationQuality.NONE,
    val gpsAccuracyM: Float? = null,
    val counts: Map<DefectType, Int> = emptyMap(),
    val overlay: List<OverlayBox> = emptyList(),
    val modelVersion: String = "",
    val isMock: Boolean = false,
    val inferenceMs: Long = 0,
    val locationPermissionMissing: Boolean = false,
    val cameraError: String? = null,
    /** User-facing notice (start failure, auto-pause...). Cleared with [InspectionController.clearMessage]. */
    val message: String? = null,
) {
    val total: Int get() = counts.values.sum()
    val isRunning: Boolean get() = phase == Phase.ACTIVE || phase == Phase.PAUSED
}
