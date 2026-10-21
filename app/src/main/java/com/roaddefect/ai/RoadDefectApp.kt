package com.roaddefect.ai

import android.app.Application
import com.roaddefect.ai.ai.DetectorFactory
import com.roaddefect.ai.config.ConfigStore
import com.roaddefect.ai.data.ImageStore
import com.roaddefect.ai.data.InspectionRepository
import com.roaddefect.ai.data.RoadDefectDatabase
import com.roaddefect.ai.inspection.InspectionController
import com.roaddefect.ai.location.LocationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import java.io.File

/** Manual dependency container: small app, no DI framework needed. */
class AppContainer(private val app: Application) {
    val repository = InspectionRepository(RoadDefectDatabase.get(app), ImageStore(app))
    val configStore = ConfigStore(app)
    val location = LocationService(app)
    val controller = InspectionController(
        repository = repository,
        location = location,
        configStore = configStore,
        detectorFactory = { forceMock -> DetectorFactory.create(app, forceMock) },
    )

    fun hasTrainedModel(): Boolean = DetectorFactory.hasTrainedModel(app)

    /** Which detector would run right now (shown in Home). */
    val activeDetectorIsMock: Boolean
        get() = DetectorFactory.willUseMock(app, configStore.forceMock)

    init {
        // Anything still ACTIVE/PAUSED at process start was killed mid-inspection; data is kept.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { repository.recoverInterrupted() }
    }
}

class RoadDefectApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Configuration.getInstance().apply {
            load(this@RoadDefectApp, getSharedPreferences("osmdroid", MODE_PRIVATE))
            userAgentValue = packageName
            osmdroidBasePath = File(filesDir, "osm")
            osmdroidTileCache = File(cacheDir, "osm-tiles")
        }
    }
}
