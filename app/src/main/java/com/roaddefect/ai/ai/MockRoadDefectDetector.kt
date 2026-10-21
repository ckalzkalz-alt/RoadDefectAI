package com.roaddefect.ai.ai

import kotlin.random.Random

/**
 * DEVELOPMENT / TEST ONLY. Produces synthetic detections on a schedule so the rest of the pipeline
 * (overlay, tracking, GPS stamping, storage, map) can be exercised without a trained model.
 *
 * These detections say NOTHING about real road condition or real AI performance. The app shows a
 * "MOCK DETECTOR" banner while this is active and stores the mock model version on the inspection.
 *
 * Behaviour: every [cycleFrames] frames a synthetic defect "appears" and is returned for
 * [visibleFrames] consecutive frames with a slowly drifting box, mimicking a real defect passing
 * through the view. That makes the deduplication logic observable.
 */
class MockRoadDefectDetector(
    private val cycleFrames: Int = 40,
    private val visibleFrames: Int = 6,
    seed: Long = 42L,
) : RoadDefectDetector {

    override val modelVersion: String = "MOCK-DETECTOR (synthetic, not a trained model)"
    override val isMock: Boolean = true

    private val random = Random(seed)
    private var frameCounter = 0
    private var currentType: DefectType = DefectType.POTHOLE
    private var baseBox = BoundingBox(0.3f, 0.5f, 0.5f, 0.65f)

    override fun initialize() {
        frameCounter = 0
    }

    override fun detect(frame: DetectorFrame): List<DefectDetection> {
        val phase = frameCounter % cycleFrames
        frameCounter++
        if (phase == 0) {
            currentType = DefectType.entries[random.nextInt(DefectType.entries.size)]
            val w = 0.15f + random.nextFloat() * 0.2f
            val h = 0.1f + random.nextFloat() * 0.15f
            val l = 0.1f + random.nextFloat() * (0.8f - w)
            val t = 0.4f + random.nextFloat() * (0.55f - h)
            baseBox = BoundingBox(l, t, l + w, t + h)
        }
        if (phase >= visibleFrames) return emptyList()

        val drift = phase * 0.015f // defect moves toward the camera / down the frame
        val box = BoundingBox(
            baseBox.left,
            (baseBox.top + drift).coerceAtMost(0.9f),
            baseBox.right,
            (baseBox.bottom + drift).coerceAtMost(0.98f),
        )
        val confidence = (0.82f + random.nextFloat() * 0.15f).coerceAtMost(0.99f)
        return listOf(DefectDetection(currentType, confidence, box, frame.timestamp))
    }

    override fun release() {}
}
