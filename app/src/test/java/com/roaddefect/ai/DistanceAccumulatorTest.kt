package com.roaddefect.ai

import com.roaddefect.ai.config.DetectionConfig
import com.roaddefect.ai.location.DistanceAccumulator
import com.roaddefect.ai.location.Geo
import com.roaddefect.ai.location.LocationFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DistanceAccumulatorTest {
    private val cfg = DetectionConfig()
    private fun fix(lat: Double, t: Long, acc: Float = 5f) = LocationFix(lat, 28.0, acc, 10f, t)

    @Test fun `haversine one hundredth of a degree of latitude is about 1112 m`() {
        assertEquals(1112.0, Geo.distanceM(-26.0, 28.0, -26.01, 28.0), 5.0)
    }

    @Test fun `accumulates driven distance`() {
        val acc = DistanceAccumulator { cfg }
        var t = 0L
        for (i in 0..10) { acc.onFix(fix(-26.0 - i * 0.0003, t)); t += 2000 } // ~33 m / 2 s steps
        assertEquals(10 * 33.4, acc.totalM, 15.0)
    }

    @Test fun `jitter while stationary adds nothing`() {
        val acc = DistanceAccumulator { cfg }
        var t = 0L
        listOf(0.0, 0.000005, -0.000004, 0.000006, 0.0).forEach { acc.onFix(fix(-26.0 + it, t)); t += 1000 }
        assertEquals(0.0, acc.totalM, 0.001)
    }

    @Test fun `low accuracy fixes are ignored`() {
        val acc = DistanceAccumulator { cfg }
        acc.onFix(fix(-26.0, 0))
        acc.onFix(fix(-26.001, 2000, acc = 80f))
        assertEquals(0.0, acc.totalM, 0.001)
    }

    @Test fun `impossible jump is not counted`() {
        val acc = DistanceAccumulator { cfg }
        acc.onFix(fix(-26.0, 0))
        acc.onFix(fix(-26.5, 1000)) // ~55 km in 1 s
        assertEquals(0.0, acc.totalM, 0.001)
    }

    @Test fun `breakSegment prevents the paused gap being counted`() {
        val acc = DistanceAccumulator { cfg }
        acc.onFix(fix(-26.0, 0)); acc.onFix(fix(-26.0003, 2000))
        val before = acc.totalM
        acc.breakSegment()
        acc.onFix(fix(-26.05, 600_000)) // moved 5 km while paused
        assertEquals(before, acc.totalM, 0.001)
    }
}
