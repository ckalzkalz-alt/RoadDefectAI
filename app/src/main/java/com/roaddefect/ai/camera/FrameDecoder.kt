package com.roaddefect.ai.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log

/**
 * Prepares a raw frame for the detector: downscale to [maxSidePx], then rotate upright.
 *
 * Extracted from [FrameAnalyzer] so the camera path and the video-validation path preprocess frames
 * IDENTICALLY. If they diverged, video validation would be measuring a different pipeline than the
 * one that runs on a real drive, which would defeat its purpose.
 *
 * Memory: the downscale happens BEFORE rotation, because a rotated copy of a full-resolution frame
 * costs tens of MB and would churn the heap over a long run.
 *
 * Ownership: the caller owns both the input bitmap it passed in and the returned [Prepared.bitmap],
 * and must recycle both. Every intermediate bitmap is recycled here.
 */
object FrameDecoder {

    private const val TAG = "FrameDecoder"

    fun prepare(raw: Bitmap, maxSidePx: Int, rotationDegrees: Int): Bitmap {
        var current = raw
        try {
            val longest = maxOf(current.width, current.height)
            if (longest > maxSidePx && longest > 0) {
                val scale = maxSidePx.toFloat() / longest
                val scaled = Bitmap.createScaledBitmap(
                    current,
                    maxOf(1, (current.width * scale).toInt()),
                    maxOf(1, (current.height * scale).toInt()),
                    true,
                )
                if (scaled !== current) {
                    if (current !== raw && !current.isRecycled) current.recycle()
                    current = scaled
                }
            }
            if (rotationDegrees != 0) {
                val m = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                val rotated = Bitmap.createBitmap(current, 0, 0, current.width, current.height, m, true)
                if (rotated !== current) {
                    if (current !== raw && !current.isRecycled) current.recycle()
                    current = rotated
                }
            }
            return current
        } catch (t: Throwable) {
            Log.e(TAG, "Frame preparation failed", t)
            throw t
        }
    }
}
