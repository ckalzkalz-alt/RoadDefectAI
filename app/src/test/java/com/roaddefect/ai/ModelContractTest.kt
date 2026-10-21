package com.roaddefect.ai

import com.roaddefect.ai.ai.BoundingBox
import com.roaddefect.ai.ai.DefectDetection
import com.roaddefect.ai.ai.DefectStatus
import com.roaddefect.ai.ai.DefectType
import com.roaddefect.ai.ai.TfliteRoadDefectDetector
import com.roaddefect.ai.camera.CameraRules
import com.roaddefect.ai.config.DetectionConfig
import com.roaddefect.ai.tracking.DefectTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelContractTest {

    @Test fun `exactly the four MVP classes exist in training order`() {
        assertEquals(
            listOf("pothole", "longitudinal_crack", "transverse_crack", "alligator_crack"),
            DefectType.entries.sortedBy { it.modelClassIndex }.map { it.datasetLabel },
        )
        assertEquals(listOf(0, 1, 2, 3), DefectType.entries.map { it.modelClassIndex }.sorted())
    }

    @Test fun `class indices map to the right type and unknown indices are rejected`() {
        assertEquals(DefectType.POTHOLE, DefectType.fromModelClassIndex(0))
        assertEquals(DefectType.ALLIGATOR_CRACK, DefectType.fromModelClassIndex(3))
        assertNull(DefectType.fromModelClassIndex(4))
        assertNull(DefectType.fromModelClassIndex(-1))
    }

    @Test fun `crack categories are never merged`() {
        val cracks = DefectType.entries.filter { it.name.endsWith("CRACK") }
        assertEquals(3, cracks.size)
        assertEquals(3, cracks.map { it.displayName }.toSet().size)
    }

    @Test fun `statuses are the three required ones and parsing falls back safely`() {
        assertEquals(setOf("AI_DETECTED", "CONFIRMED", "REJECTED"), DefectStatus.entries.map { it.name }.toSet())
        assertEquals(DefectStatus.AI_DETECTED, DefectStatus.fromName("garbage"))
    }

    @Test fun `well formed detection validates`() {
        assertTrue(DefectDetection(DefectType.POTHOLE, 0.9f, BoundingBox(0.1f, 0.1f, 0.4f, 0.4f), 0).isWellFormed())
    }

    @Test fun `invalid detections are flagged`() {
        val ok = BoundingBox(0.1f, 0.1f, 0.4f, 0.4f)
        assertFalse(DefectDetection(DefectType.POTHOLE, Float.NaN, ok, 0).isWellFormed())
        assertFalse(DefectDetection(DefectType.POTHOLE, 1.01f, ok, 0).isWellFormed())
        assertFalse(DefectDetection(DefectType.POTHOLE, 0.9f, BoundingBox(0.4f, 0.4f, 0.1f, 0.1f), 0).isWellFormed())
        assertFalse(DefectDetection(DefectType.POTHOLE, 0.9f, BoundingBox(-0.5f, 0f, 0.4f, 0.4f), 0).isWellFormed())
    }

    @Test fun `iou of identical boxes is one and disjoint boxes is zero`() {
        val a = BoundingBox(0.1f, 0.1f, 0.5f, 0.5f)
        assertEquals(1f, a.iou(a), 1e-5f)
        assertEquals(0f, a.iou(BoundingBox(0.6f, 0.6f, 0.9f, 0.9f)), 1e-5f)
    }

    @Test fun `default minimum confidence is 70 percent and is configurable`() {
        assertEquals(0.70f, DetectionConfig().minConfidence, 0f)
        val strict = DetectionConfig(minConfidence = 0.9f)
        val tr = DefectTracker { strict }
        val box = BoundingBox(0.3f, 0.5f, 0.5f, 0.65f)
        // 0.85 passes the default but not the stricter configured threshold
        val events = (0 until 5).flatMap { tr.update(listOf(DefectDetection(DefectType.POTHOLE, 0.85f, box, 0)), it * 200L, null) }
        assertTrue(events.isEmpty())
    }

    @Test fun `pixel space export is rescaled to normalised and normalised export is left alone`() {
        // Real RDD2022 export measured 2.77..639.0 at input 640 -> must be divided down.
        assertEquals(1f / 640f, TfliteRoadDefectDetector.boxScaleMultiplier(639.0f, 640), 1e-9f)
        // A contract-compliant export stays untouched.
        assertEquals(1f, TfliteRoadDefectDetector.boxScaleMultiplier(0.98f, 640), 1e-9f)
        // Exactly-at-boundary is still normalised; only clearly-pixel values switch.
        assertEquals(1f, TfliteRoadDefectDetector.boxScaleMultiplier(1.5f, 640), 1e-9f)
        assertEquals(1f / 320f, TfliteRoadDefectDetector.boxScaleMultiplier(1.6f, 320), 1e-9f)
    }

    @Test fun `a decoded pixel box survives rescaling without being clamped to a sliver`() {
        val inputSize = 640
        val scale = TfliteRoadDefectDetector.boxScaleMultiplier(639.0f, inputSize)
        // cx=174.6, cy=101.6, w=180.8, h=42.1 measured from the trained model.
        val cx = 174.6f * scale; val cy = 101.6f * scale
        val w = 180.8f * scale; val h = 42.1f * scale
        val box = BoundingBox(
            (cx - w / 2f).coerceIn(0f, 1f), (cy - h / 2f).coerceIn(0f, 1f),
            (cx + w / 2f).coerceIn(0f, 1f), (cy + h / 2f).coerceIn(0f, 1f),
        )
        assertTrue(box.isValid())
        assertEquals(174.6f / 640f, box.centerX, 1e-4f)
        assertEquals(180.8f / 640f, box.width, 1e-4f)
        // Without the rescale the same values collapse into an empty box.
        val unscaled = BoundingBox(
            (174.6f - 180.8f / 2f).coerceIn(0f, 1f), (101.6f - 42.1f / 2f).coerceIn(0f, 1f),
            (174.6f + 180.8f / 2f).coerceIn(0f, 1f), (101.6f + 42.1f / 2f).coerceIn(0f, 1f),
        )
        assertFalse(unscaled.isValid())
    }
}

class CameraRulesTest {
    @Test fun `camera permission missing blocks the inspection`() =
        assertEquals(CameraRules.Readiness.BLOCKED_NO_CAMERA, CameraRules.readiness(false, true))

    @Test fun `camera granted but no location still allows detection without GPS`() =
        assertEquals(CameraRules.Readiness.READY_WITHOUT_GPS, CameraRules.readiness(true, false))

    @Test fun `all permissions granted is ready`() =
        assertEquals(CameraRules.Readiness.READY, CameraRules.readiness(true, true))

    @Test fun `rotation - landscape gets 16 by 9 and portrait 9 by 16`() {
        assertEquals(16f / 9f, CameraRules.previewAspect(2400, 1080), 1e-5f)
        assertEquals(9f / 16f, CameraRules.previewAspect(1080, 2400), 1e-5f)
    }
}
