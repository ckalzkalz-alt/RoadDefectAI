package com.roaddefect.ai

import com.roaddefect.ai.ai.BoundingBox
import com.roaddefect.ai.ai.DefectDetection
import com.roaddefect.ai.ai.DefectType
import com.roaddefect.ai.video.Letterbox
import com.roaddefect.ai.video.VideoDetectionRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the pure logic of the video-validation path: frame scheduling and the filtering the runner
 * applies before reporting a detection. The decode/inference loop itself needs a device, so it is
 * exercised manually on the emulator with a known video.
 */
class VideoValidationTest {

    /** Mirrors the runner's sampling loop: sample at t, then step by the interval. */
    private fun schedule(durationMs: Long, intervalMs: Long): List<Long> {
        val out = ArrayList<Long>()
        var t = 0L
        while (t <= durationMs) {
            out.add(t)
            t += intervalMs
        }
        return out
    }

    @Test fun `sampling covers the whole video at the chosen interval`() {
        // 10s at 500ms => t = 0,500,...,10000 => 21 samples, including t=0 and the final frame.
        val s = schedule(10_000, 500)
        assertEquals(21, s.size)
        assertEquals(0L, s.first())
        assertEquals(10_000L, s.last())
    }

    @Test fun `a duration that is not a multiple of the interval still samples the end`() {
        val s = schedule(9_750, 500)
        assertEquals(0L, s.first())
        assertEquals(9_500L, s.last())
        assertTrue("must not sample past the end", s.all { it <= 9_750 })
    }

    @Test fun `samples are strictly increasing so a result maps to one video position`() {
        val s = schedule(5_000, 250)
        assertEquals(s, s.sorted())
        assertEquals(s.size, s.toSet().size)
    }

    @Test fun `a shorter interval analyses more frames`() {
        assertTrue(schedule(5_000, 100).size > schedule(5_000, 500).size)
    }

    @Test fun `progress counts frames and frames-with-detections`() {
        val withDet = VideoDetectionRunner.Progress(
            3, 2,
            VideoDetectionRunner.FrameResult(
                2, 1_000,
                listOf(DefectDetection(DefectType.POTHOLE, 0.9f, BoundingBox(0.1f, 0.1f, 0.3f, 0.3f), 1_000)),
            ),
        )
        assertEquals(3, withDet.framesAnalysed)
        assertEquals(2, withDet.framesWithDetections)
        assertEquals(1, withDet.last!!.detections.size)

        val empty = VideoDetectionRunner.Progress(1, 0, null)
        assertEquals(0, empty.framesWithDetections)
        assertEquals(null, empty.last)
    }

    @Test fun `confidence and well-formedness gates drop unusable detections`() {
        val minConfidence = 0.25f
        fun kept(d: DefectDetection) = d.isWellFormed() && d.confidence >= minConfidence

        val ok = DefectDetection(DefectType.POTHOLE, 0.9f, BoundingBox(0.1f, 0.1f, 0.3f, 0.3f), 0)
        val tooWeak = DefectDetection(DefectType.POTHOLE, 0.1f, BoundingBox(0.1f, 0.1f, 0.3f, 0.3f), 0)
        val degenerate = DefectDetection(DefectType.POTHOLE, 0.9f, BoundingBox(0.3f, 0.3f, 0.1f, 0.1f), 0)
        val nan = DefectDetection(DefectType.POTHOLE, Float.NaN, BoundingBox(0.1f, 0.1f, 0.3f, 0.3f), 0)

        assertTrue(kept(ok))
        assertTrue(!kept(tooWeak))
        assertTrue(!kept(degenerate))
        assertTrue(!kept(nan))
    }

    @Test fun `default interval and confidence match the runner constants`() {
        assertEquals(500L, VideoDetectionRunner.DEFAULT_INTERVAL_MS)
        assertEquals(0.25f, VideoDetectionRunner.DEFAULT_MIN_CONFIDENCE, 1e-6f)
    }

    // --- Letterbox: detects the black bars that made a real video look like it was not playing ---

    /** Builds a frame: `bar` rows of black at top/bottom, `content` bright rows between. */
    private fun frame(width: Int, topBar: Int, content: Int, bottomBar: Int, contentLum: Int = 200): Pair<IntArray, Int> {
        val height = topBar + content + bottomBar
        val lum = IntArray(width * height)
        for (y in 0 until height) {
            val inBar = y < topBar || y >= topBar + content
            val v = if (inBar) 0 else contentLum
            for (x in 0 until width) lum[y * width + x] = v
        }
        return lum to height
    }

    @Test fun `letterbox bars are found and excluded`() {
        val (lum, h) = frame(width = 100, topBar = 40, content = 20, bottomBar = 40)
        val rows = Letterbox.contentRows(lum, 100, h)
        assertEquals(40, rows.first)
        assertEquals(59, rows.last)
    }

    @Test fun `a frame with no bars is left alone`() {
        val (lum, h) = frame(width = 100, topBar = 0, content = 100, bottomBar = 0)
        val rows = Letterbox.contentRows(lum, 100, h)
        assertEquals(0 until h, rows)
    }

    @Test fun `an entirely black frame is not treated as content`() {
        val width = 100
        val h = 60
        val lum = IntArray(width * h) // all zero
        assertEquals(0 until h, Letterbox.contentRows(lum, width, h))
    }

    @Test fun `dark but uniform footage is not mistaken for bars`() {
        // A night frame: low luminance everywhere, no black bars. Must not be cropped.
        val (lum, h) = frame(width = 80, topBar = 0, content = 50, bottomBar = 0, contentLum = 20)
        assertEquals(0 until h, Letterbox.contentRows(lum, 80, h))
    }

    @Test fun `the real Video 2 geometry is flagged as heavily letterboxed`() {
        // Measured from `Video 2/Road assesment.mp4`: 720x1280 with a 294-row picture.
        val (lum, h) = frame(width = 720, topBar = 524, content = 294, bottomBar = 462)
        assertTrue(Letterbox.isHeavilyLetterboxed(lum, 720, h))
        val rows = Letterbox.contentRows(lum, 720, h)
        assertEquals(524, rows.first)
        assertEquals(817, rows.last)
    }

    @Test fun `normal footage is never flagged`() {
        val (lum, h) = frame(width = 640, topBar = 0, content = 640, bottomBar = 0)
        assertTrue(!Letterbox.isHeavilyLetterboxed(lum, 640, h))
    }

    @Test fun `a bar on only one side is not a letterbox`() {
        // A dark sky at the top of an otherwise full frame is NOT a letterbox.
        val (lum, h) = frame(width = 100, topBar = 30, content = 130, bottomBar = 0)
        assertEquals(0 until h, Letterbox.contentRows(lum, 100, h))
        assertTrue(!Letterbox.isHeavilyLetterboxed(lum, 100, h))
    }

    @Test fun `degenerate inputs fall back to the full frame`() {
        assertEquals(0 until 0, Letterbox.contentRows(IntArray(0), 0, 0))
        assertEquals(0 until 10, Letterbox.contentRows(IntArray(5), 10, 10))
        assertTrue(!Letterbox.isHeavilyLetterboxed(IntArray(0), 0, 0))
    }
}
