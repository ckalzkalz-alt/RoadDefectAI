package com.roaddefect.ai.camera

import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.roaddefect.ai.inspection.InspectionController

/**
 * CameraX analyser. Runs on a dedicated single thread. Drops frames cheaply (before any bitmap work)
 * when the configured inference rate says it is not time yet, so the preview stays smooth.
 *
 * Frame preparation (downscale + rotate) lives in [FrameDecoder] so the video-validation path
 * preprocesses frames identically.
 */
class FrameAnalyzer(private val controller: InspectionController) : ImageAnalysis.Analyzer {

    override fun analyze(image: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            if (!controller.shouldAnalyse(now)) return

            val raw = image.toBitmap()
            var current: Bitmap? = null
            try {
                current = FrameDecoder.prepare(raw, controller.frameMaxSidePx(), image.imageInfo.rotationDegrees)
                controller.processFrame(current, now)
            } finally {
                current?.let { if (!it.isRecycled) it.recycle() }
                if (!raw.isRecycled) raw.recycle()
            }
        } catch (t: Throwable) {
            Log.e("FrameAnalyzer", "Frame analysis failed", t)
        } finally {
            image.close()
        }
    }
}
