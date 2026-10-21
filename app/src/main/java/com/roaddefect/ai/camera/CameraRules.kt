package com.roaddefect.ai.camera

/** Pure rules kept out of Composables so they can be unit tested. */
object CameraRules {

    enum class Readiness { READY, READY_WITHOUT_GPS, BLOCKED_NO_CAMERA }

    /** Camera is mandatory. Missing GPS still allows detection, but defects are saved without coordinates. */
    fun readiness(cameraGranted: Boolean, locationGranted: Boolean): Readiness = when {
        !cameraGranted -> Readiness.BLOCKED_NO_CAMERA
        !locationGranted -> Readiness.READY_WITHOUT_GPS
        else -> Readiness.READY
    }

    /**
     * Width/height of the camera area for a given screen shape. Analysis and preview both use a 16:9
     * sensor stream, so the overlay maps 1:1 onto the preview when the area has this aspect ratio.
     */
    fun previewAspect(screenWidthPx: Int, screenHeightPx: Int): Float =
        if (screenWidthPx >= screenHeightPx) 16f / 9f else 9f / 16f
}
