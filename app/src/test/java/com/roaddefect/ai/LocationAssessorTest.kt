package com.roaddefect.ai

import com.roaddefect.ai.config.DetectionConfig
import com.roaddefect.ai.location.LocationAssessor
import com.roaddefect.ai.location.LocationFix
import com.roaddefect.ai.location.LocationQuality
import org.junit.Assert.assertEquals
import org.junit.Test

class LocationAssessorTest {
    private val cfg = DetectionConfig(lowAccuracyThresholdM = 20f, gpsStaleMs = 5000)
    private val now = 100_000L
    private fun fix(acc: Float = 5f, lat: Double = -26.2, lon: Double = 28.0, t: Long = now) = LocationFix(lat, lon, acc, 10f, t)

    @Test fun `valid fresh accurate fix is GOOD`() = assertEquals(LocationQuality.GOOD, LocationAssessor.assess(fix(), now, cfg))
    @Test fun `accuracy exactly at the threshold is still GOOD`() = assertEquals(LocationQuality.GOOD, LocationAssessor.assess(fix(acc = 20f), now, cfg))
    @Test fun `poor accuracy is LOW not silently GOOD`() = assertEquals(LocationQuality.LOW, LocationAssessor.assess(fix(acc = 45f), now, cfg))
    @Test fun `unknown accuracy is LOW`() = assertEquals(LocationQuality.LOW, LocationAssessor.assess(fix(acc = Float.NaN), now, cfg))
    @Test fun `no fix at all is NONE`() = assertEquals(LocationQuality.NONE, LocationAssessor.assess(null, now, cfg))
    @Test fun `temporary GPS loss - stale fix becomes NONE`() = assertEquals(LocationQuality.NONE, LocationAssessor.assess(fix(t = now - 6000), now, cfg))
    @Test fun `fix just inside the stale window is still usable`() = assertEquals(LocationQuality.GOOD, LocationAssessor.assess(fix(t = now - 4000), now, cfg))
    @Test fun `out of range latitude is NONE`() = assertEquals(LocationQuality.NONE, LocationAssessor.assess(fix(lat = 95.0), now, cfg))
    @Test fun `NaN longitude is NONE`() = assertEquals(LocationQuality.NONE, LocationAssessor.assess(fix(lon = Double.NaN), now, cfg))
    @Test fun `GPS recovers after loss`() {
        assertEquals(LocationQuality.NONE, LocationAssessor.assess(fix(t = now - 9000), now, cfg))
        assertEquals(LocationQuality.GOOD, LocationAssessor.assess(fix(t = now + 1000), now + 1000, cfg))
    }
}
