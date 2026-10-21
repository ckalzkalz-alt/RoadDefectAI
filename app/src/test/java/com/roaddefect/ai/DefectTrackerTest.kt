package com.roaddefect.ai

import com.roaddefect.ai.ai.BoundingBox
import com.roaddefect.ai.ai.DefectDetection
import com.roaddefect.ai.ai.DefectType
import com.roaddefect.ai.config.DetectionConfig
import com.roaddefect.ai.location.LocationFix
import com.roaddefect.ai.tracking.DefectTracker
import com.roaddefect.ai.tracking.DefectTracker.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefectTrackerTest {

    private val cfg = DetectionConfig()
    private fun tracker(c: DetectionConfig = cfg) = DefectTracker { c }

    private fun det(type: DefectType, conf: Float, box: BoundingBox = BoundingBox(0.3f, 0.5f, 0.5f, 0.65f), t: Long = 0) =
        DefectDetection(type, conf, box, t)

    private fun fix(lat: Double = -26.0, lon: Double = 28.0, t: Long = 0) = LocationFix(lat, lon, 5f, 11f, t)

    private fun drifting(i: Int) = BoundingBox(0.3f, 0.5f + i * 0.015f, 0.5f, 0.65f + i * 0.015f)

    @Test fun `one pothole seen over many frames creates exactly one record`() {
        val tr = tracker()
        val confirmed = ArrayList<Event>()
        val confidences = listOf(0.80f, 0.82f, 0.85f, 0.83f, 0.84f, 0.86f, 0.81f, 0.80f)
        confidences.forEachIndexed { i, conf ->
            val now = i * 200L
            confirmed += tr.update(listOf(det(DefectType.POTHOLE, conf, drifting(i), now)), now, fix(t = now))
                .filterIsInstance<Event.Confirmed>()
        }
        assertEquals(1, confirmed.size)
        assertEquals(DefectType.POTHOLE, confirmed[0].detection.type)
    }

    @Test fun `spec example 92 94 95 93 percent yields one record`() {
        val tr = tracker()
        val confirmed = listOf(0.92f, 0.94f, 0.95f, 0.93f).flatMapIndexed { i, c ->
            tr.update(listOf(det(DefectType.POTHOLE, c, drifting(i))), i * 200L, fix())
        }.filterIsInstance<Event.Confirmed>()
        assertEquals(1, confirmed.size)
    }

    @Test fun `detections below the confidence threshold never create records`() {
        val tr = tracker()
        val events = (0 until 20).flatMap { i -> tr.update(listOf(det(DefectType.POTHOLE, 0.69f, drifting(i % 5))), i * 200L, fix()) }
        assertTrue(events.isEmpty())
    }

    @Test fun `detection exactly at threshold counts`() {
        val tr = tracker()
        val events = (0 until 3).flatMap { i -> tr.update(listOf(det(DefectType.TRANSVERSE_CRACK, 0.70f, drifting(i))), i * 200L, fix()) }
        assertEquals(1, events.filterIsInstance<Event.Confirmed>().size)
    }

    @Test fun `a single weak sighting is not recorded`() {
        val tr = tracker()
        val events = tr.update(listOf(det(DefectType.POTHOLE, 0.75f)), 0, fix())
        assertTrue(events.isEmpty())
    }

    @Test fun `a single very confident sighting is recorded immediately`() {
        val tr = tracker()
        val events = tr.update(listOf(det(DefectType.POTHOLE, 0.95f)), 0, fix())
        assertEquals(1, events.filterIsInstance<Event.Confirmed>().size)
    }

    @Test fun `same type close in time and space is suppressed as duplicate`() {
        val tr = tracker()
        tr.update(listOf(det(DefectType.POTHOLE, 0.95f)), 0, fix())
        // Track lost, a new track for the "same" pothole appears 2 s later, 2 m away.
        val events = tr.update(listOf(det(DefectType.POTHOLE, 0.96f, BoundingBox(0.7f, 0.2f, 0.9f, 0.3f))), 2000, fix(lat = -26.00002, t = 2000))
        assertTrue(events.filterIsInstance<Event.Confirmed>().isEmpty())
    }

    @Test fun `same type far enough away is recorded as a new defect`() {
        val tr = tracker()
        tr.update(listOf(det(DefectType.POTHOLE, 0.95f)), 0, fix())
        // ~22 m north, 2 s later: well beyond the 8 m threshold.
        val events = tr.update(listOf(det(DefectType.POTHOLE, 0.96f, BoundingBox(0.7f, 0.2f, 0.9f, 0.3f))), 2000, fix(lat = -26.0 + 0.0002, t = 2000))
        assertEquals(1, events.filterIsInstance<Event.Confirmed>().size)
    }

    @Test fun `same place after the cooldown is recorded again`() {
        val c = cfg.copy(cooldownMs = 4000)
        val tr = tracker(c)
        tr.update(listOf(det(DefectType.POTHOLE, 0.95f)), 0, fix())
        val events = tr.update(listOf(det(DefectType.POTHOLE, 0.95f, BoundingBox(0.7f, 0.2f, 0.9f, 0.3f))), 6000, fix(t = 6000))
        assertEquals(1, events.filterIsInstance<Event.Confirmed>().size)
    }

    @Test fun `different types at the same moment are separate records`() {
        val tr = tracker()
        val events = tr.update(
            listOf(
                det(DefectType.POTHOLE, 0.95f, BoundingBox(0.1f, 0.5f, 0.3f, 0.7f)),
                det(DefectType.ALLIGATOR_CRACK, 0.94f, BoundingBox(0.5f, 0.5f, 0.9f, 0.9f)),
            ), 0, fix(),
        )
        assertEquals(setOf(DefectType.POTHOLE, DefectType.ALLIGATOR_CRACK), events.map { it.detection.type }.toSet())
    }

    @Test fun `without GPS only the time cooldown applies`() {
        val tr = tracker()
        tr.update(listOf(det(DefectType.POTHOLE, 0.95f)), 0, null)
        val events = tr.update(listOf(det(DefectType.POTHOLE, 0.95f, BoundingBox(0.7f, 0.2f, 0.9f, 0.3f))), 1000, null)
        assertTrue(events.isEmpty())
    }

    @Test fun `a clearly better later frame emits Improved and carries that frame's detection`() {
        val tr = tracker()
        tr.update(listOf(det(DefectType.POTHOLE, 0.93f, drifting(0), 0)), 0, fix())
        val events = tr.update(listOf(det(DefectType.POTHOLE, 0.99f, drifting(1), 200)), 200, fix())
        val improved = events.filterIsInstance<Event.Improved>()
        assertEquals(1, improved.size)
        assertEquals(200L, improved[0].detection.timestamp)
    }

    @Test fun `improvements are bounded per track`() {
        val tr = tracker(cfg.copy(minConfidence = 0.5f))
        tr.update(listOf(det(DefectType.POTHOLE, 0.93f, drifting(0))), 0, fix())
        var improved = 0
        var conf = 0.94f
        for (i in 1..30) {
            conf = (conf + 0.003f).coerceAtMost(0.999f)
            improved += tr.update(listOf(det(DefectType.POTHOLE, conf, drifting(i % 10))), i * 100L, fix())
                .count { it is Event.Improved }
        }
        assertTrue("improvements=$improved", improved <= 3)
    }

    @Test fun `malformed detections are ignored without throwing`() {
        val tr = tracker()
        val bad = listOf(
            det(DefectType.POTHOLE, Float.NaN),
            det(DefectType.POTHOLE, 1.5f),
            det(DefectType.POTHOLE, -0.2f),
            det(DefectType.POTHOLE, 0.9f, BoundingBox(0.5f, 0.5f, 0.4f, 0.6f)),     // inverted
            det(DefectType.POTHOLE, 0.9f, BoundingBox(0.2f, 0.2f, 1.9f, 0.6f)),     // outside frame
            det(DefectType.POTHOLE, 0.9f, BoundingBox(Float.NaN, 0.2f, 0.5f, 0.6f)),
        )
        assertTrue(tr.update(bad, 0, fix()).isEmpty())
    }

    @Test fun `reset forgets open tracks and recent records`() {
        val tr = tracker()
        tr.update(listOf(det(DefectType.POTHOLE, 0.95f)), 0, fix())
        tr.reset()
        val events = tr.update(listOf(det(DefectType.POTHOLE, 0.95f)), 500, fix(t = 500))
        assertEquals(1, events.filterIsInstance<Event.Confirmed>().size)
    }
}
