package com.roaddefect.ai

import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.roaddefect.ai.ai.DefectDetection
import com.roaddefect.ai.ai.DefectStatus
import com.roaddefect.ai.ai.DefectType
import com.roaddefect.ai.ai.DetectorFrame
import com.roaddefect.ai.ai.BoundingBox
import com.roaddefect.ai.ai.MockRoadDefectDetector
import com.roaddefect.ai.ai.RoadDefectDetector
import com.roaddefect.ai.config.ConfigStore
import com.roaddefect.ai.data.ImageStore
import com.roaddefect.ai.data.InspectionRepository
import com.roaddefect.ai.data.InspectionStatus
import com.roaddefect.ai.data.RoadDefectDatabase
import com.roaddefect.ai.inspection.InspectionController
import com.roaddefect.ai.inspection.Phase
import com.roaddefect.ai.location.LocationFix
import com.roaddefect.ai.location.LocationSource
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

private class FakeLocation : LocationSource {
    val flow = MutableStateFlow<LocationFix?>(null)
    var permission = true
    override val fix: StateFlow<LocationFix?> = flow
    override fun start() = permission
    override fun stop() { flow.value = null }
}

/** Always sees one confident pothole, so a defect is recorded on the first analysed frame. */
private class AlwaysPothole : RoadDefectDetector {
    override val modelVersion = "TEST-MODEL"
    override val isMock = true
    override fun initialize() {}
    override fun detect(frame: DetectorFrame) =
        listOf(DefectDetection(DefectType.POTHOLE, 0.97f, BoundingBox(0.3f, 0.5f, 0.5f, 0.65f), frame.timestamp))
    override fun release() {}
}

private class FailingDetector : RoadDefectDetector {
    override val modelVersion = "BROKEN"
    override val isMock = false
    override fun initialize() { error("model file corrupt") }
    override fun detect(frame: DetectorFrame) = emptyList<DefectDetection>()
    override fun release() {}
}

@RunWith(AndroidJUnit4::class)
class InspectionFlowTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
    private lateinit var db: RoadDefectDatabase
    private lateinit var repo: InspectionRepository
    private lateinit var location: FakeLocation
    private lateinit var controller: InspectionController

    private fun newController(detector: () -> RoadDefectDetector) =
        InspectionController(repo, location, ConfigStore(ctx), { detector() })

    private fun waitFor(timeoutMs: Long = 5000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) { check(System.currentTimeMillis() < end) { "timed out" }; Thread.sleep(25) }
    }

    private fun frame(): Bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ctx, RoadDefectDatabase::class.java).allowMainThreadQueries().build()
        repo = InspectionRepository(db, ImageStore(ctx))
        location = FakeLocation()
        location.flow.value = LocationFix(-26.2041, 28.0473, 5f, 11f, System.currentTimeMillis())
        controller = newController { AlwaysPothole() }
    }

    @After fun tearDown() { db.close() }

    @Test fun start_pause_resume_stop_and_reopen() = runBlocking {
        controller.start()
        waitFor { controller.state.value.phase == Phase.ACTIVE }
        val id = controller.state.value.inspectionId!!

        controller.pause()
        assertEquals(Phase.PAUSED, controller.state.value.phase)
        assertEquals(false, controller.shouldAnalyse(System.currentTimeMillis()))   // paused: no analysis

        controller.resume()
        assertEquals(Phase.ACTIVE, controller.state.value.phase)

        controller.stop()
        waitFor { controller.completedId.value == id }
        assertEquals(Phase.IDLE, controller.state.value.phase)

        // "Reopen": data is read back from storage.
        val saved = repo.getInspection(id)
        assertNotNull(saved)
        assertEquals(InspectionStatus.COMPLETED.name, saved!!.status)
        assertNotNull(saved.endTime)
        assertEquals("TEST-MODEL", saved.modelVersion)   // model version recorded per inspection
    }

    @Test fun defect_gets_gps_status_and_linked_image() = runBlocking {
        controller.start()
        waitFor { controller.state.value.phase == Phase.ACTIVE }
        val id = controller.state.value.inspectionId!!

        val bmp = frame()
        assertTrue(controller.shouldAnalyse(System.currentTimeMillis()))
        controller.processFrame(bmp, System.currentTimeMillis())

        val defects = repo.getDefects(id)
        assertEquals(1, defects.size)
        val d = defects[0]
        assertEquals(DefectType.POTHOLE.name, d.type)
        assertEquals(DefectStatus.AI_DETECTED.name, d.status)
        assertEquals(-26.2041, d.latitude!!, 1e-6)
        assertEquals("GOOD", d.locationQuality)
        assertEquals(id, d.inspectionId)
        // storage linkage: the referenced image exists on disk
        assertNotNull(repo.images.resolve(d.imagePath))
        controller.stop(); waitFor { controller.completedId.value == id }
    }

    @Test fun one_defect_over_many_frames_is_one_record() = runBlocking {
        controller.start()
        waitFor { controller.state.value.phase == Phase.ACTIVE }
        val id = controller.state.value.inspectionId!!
        val bmp = frame()
        repeat(15) { i -> controller.processFrame(bmp, System.currentTimeMillis() + i * 200L) }
        assertEquals(1, repo.getDefects(id).size)
        controller.stop(); waitFor { controller.completedId.value == id }
    }

    @Test fun poor_gps_is_flagged_and_lost_gps_stores_no_coordinates() = runBlocking {
        location.flow.value = LocationFix(-26.2, 28.0, 60f, 5f, System.currentTimeMillis())
        controller.start()
        waitFor { controller.state.value.phase == Phase.ACTIVE }
        val id = controller.state.value.inspectionId!!
        controller.processFrame(frame(), System.currentTimeMillis())
        assertEquals("LOW", repo.getDefects(id)[0].locationQuality)

        location.flow.value = null // GPS lost
        // different place in time so the duplicate cooldown does not hide the second record
        controller.processFrame(frame(), System.currentTimeMillis() + 60_000)
        val second = repo.getDefects(id).last()
        assertEquals("NONE", second.locationQuality)
        assertNull(second.latitude)
        controller.stop(); waitFor { controller.completedId.value == id }
    }

    @Test fun missing_location_permission_is_reported() = runBlocking {
        location.permission = false
        controller.start()
        waitFor { controller.state.value.phase == Phase.ACTIVE }
        assertTrue(controller.state.value.locationPermissionMissing)
        controller.stop(); waitFor { controller.completedId.value != null }
    }

    @Test fun failing_model_load_is_reported_and_no_inspection_is_created() = runBlocking {
        val c = newController { FailingDetector() }
        c.start()
        waitFor { c.state.value.message != null }
        assertEquals(Phase.IDLE, c.state.value.phase)
        assertTrue(c.state.value.message!!.contains("model"))
    }

    @Test fun camera_unavailable_error_surfaces_in_state() {
        controller.reportCameraError("Camera unavailable: in use")
        assertEquals("Camera unavailable: in use", controller.state.value.cameraError)
    }

    @Test fun auto_pause_when_app_leaves_foreground_keeps_data() = runBlocking {
        controller.start()
        waitFor { controller.state.value.phase == Phase.ACTIVE }
        controller.pause(auto = true)  // what MainActivity.onStop does
        assertEquals(Phase.PAUSED, controller.state.value.phase)
        assertTrue(controller.state.value.message!!.contains("paused"))
        controller.resume()
        assertEquals(Phase.ACTIVE, controller.state.value.phase)
        val id = controller.state.value.inspectionId!!
        controller.stop(); waitFor { controller.completedId.value == id }
    }

    @Test fun interrupted_inspection_is_recovered_with_data_kept() = runBlocking {
        val id = repo.startInspection(1000, "TEST", true, 0.7f)
        repo.recoverInterrupted()
        assertEquals(InspectionStatus.INTERRUPTED.name, repo.getInspection(id)!!.status)
    }

    @Test fun deleting_an_inspection_removes_defects_and_images() = runBlocking {
        controller.start()
        waitFor { controller.state.value.phase == Phase.ACTIVE }
        val id = controller.state.value.inspectionId!!
        controller.processFrame(frame(), System.currentTimeMillis())
        val path = repo.getDefects(id)[0].imagePath
        controller.stop(); waitFor { controller.completedId.value == id }
        repo.deleteInspection(id)
        assertEquals(0, repo.getDefects(id).size)
        assertNull(repo.images.resolve(path))
    }

    @Test fun mock_detector_is_labelled_and_produces_in_range_detections() {
        val m = MockRoadDefectDetector()
        m.initialize()
        assertTrue(m.isMock)
        assertTrue(m.modelVersion.contains("MOCK"))
        val f = DetectorFrame(frame(), 0)
        val all = (0 until 200).flatMap { m.detect(f) }
        assertTrue(all.isNotEmpty())
        assertTrue(all.all { it.isWellFormed() && it.type in DefectType.entries })
    }
}
