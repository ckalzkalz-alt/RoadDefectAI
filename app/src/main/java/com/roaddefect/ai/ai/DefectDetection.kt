package com.roaddefect.ai.ai

import android.graphics.Bitmap

/** Bounding box in normalised coordinates (0..1) of the upright (display-oriented) frame. */
data class BoundingBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val area: Float get() = width.coerceAtLeast(0f) * height.coerceAtLeast(0f)

    /** True when the box is well formed and fully inside 0..1 (small tolerance for rounding). */
    fun isValid(): Boolean {
        val eps = 0.01f
        return left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() &&
            right > left && bottom > top &&
            left >= -eps && top >= -eps && right <= 1f + eps && bottom <= 1f + eps
    }

    fun iou(other: BoundingBox): Float {
        val il = maxOf(left, other.left)
        val it = maxOf(top, other.top)
        val ir = minOf(right, other.right)
        val ib = minOf(bottom, other.bottom)
        val inter = (ir - il).coerceAtLeast(0f) * (ib - it).coerceAtLeast(0f)
        val union = area + other.area - inter
        return if (union <= 0f) 0f else inter / union
    }
}

/** What the AI layer returns for one detected defect in one frame. */
data class DefectDetection(
    val type: DefectType,
    val confidence: Float,
    val boundingBox: BoundingBox,
    val timestamp: Long,
) {
    /** Invalid detections (NaN / out of range confidence, degenerate boxes) are dropped by the pipeline. */
    fun isWellFormed(): Boolean =
        confidence.isFinite() && confidence in 0f..1f && boundingBox.isValid()
}

/**
 * One camera frame handed to the detector. Deliberately free of CameraX types so the AI layer
 * can be tested and replaced independently. The bitmap is already rotated upright.
 */
class DetectorFrame(
    val bitmap: Bitmap,
    val timestamp: Long,
)
