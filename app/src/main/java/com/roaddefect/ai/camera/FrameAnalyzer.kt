package com.roaddefect.ai.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.roaddefect.ai.inspection.InspectionController

/**
 * CameraX analyser. Runs on a dedicated single thread. Drops frames cheaply (before any bitmap work)
 * when the configured inference rate says it is not time yet, so the preview stays smooth.
 *
 * Memory: a frame is downscaled to the configured image size *before* rotation, because a rotated
 * copy of a full-resolution frame costs tens of MB and would churn the heap over a long inspection.
 */
class FrameAnalyzer(private val controller: InspectionController) : ImageAnalysis.Analyzer {

    override fun analyze(image: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            if (!controller.shouldAnalyse(now)) return

            val raw = image.toBitmap()
            var current = raw
            try {
                val maxSide = controller.frameMaxSidePx()
                val longest = maxOf(current.width, current.height)
                if (longest > maxSide) {
                    val scale = maxSide.toFloat() / longest
                    val scaled = Bitmap.createScaledBitmap(
                        current,
                        maxOf(1, (current.width * scale).toInt()),
                        maxOf(1, (current.height * scale).toInt()),
                        true,
                    )
                    if (scaled !== current) {
                        current.recycle()
                        current = scaled
                    }
                }
                val rotation = image.imageInfo.rotationDegrees
                if (rotation != 0) {
                    val m = Matrix().apply { postRotate(rotation.toFloat()) }
                    val rotated = Bitmap.createBitmap(current, 0, 0, current.width, current.height, m, true)
                    if (rotated !== current) {
                        current.recycle()
                        current = rotated
                    }
                }
                controller.processFrame(current, now)
            } finally {
                current.recycle()
            }
        } catch (t: Throwable) {
            Log.e("FrameAnalyzer", "Frame analysis failed", t)
        } finally {
            image.close()
        }
    }
}
