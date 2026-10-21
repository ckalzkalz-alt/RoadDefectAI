package com.roaddefect.ai.ai

/**
 * The only seam between the app and any road-defect model. Implementations:
 *  - [TfliteRoadDefectDetector]: loads a trained custom model from assets (production path).
 *  - [MockRoadDefectDetector]: synthetic detections for development/testing ONLY.
 *
 * Contract:
 *  - [initialize] is called once on a background thread before [detect].
 *  - [detect] is called from a single background thread, never the UI thread.
 *  - [release] frees native resources and may be called more than once.
 */
interface RoadDefectDetector {
    /** Human readable model identifier stored with each inspection, e.g. "RoadDefectAI-v1.0". */
    val modelVersion: String

    /** True for synthetic detectors. The UI shows a persistent banner and records this flag. */
    val isMock: Boolean

    fun initialize()

    fun detect(frame: DetectorFrame): List<DefectDetection>

    fun release()
}
