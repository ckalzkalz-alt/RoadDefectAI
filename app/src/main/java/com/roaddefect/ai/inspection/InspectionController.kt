package com.roaddefect.ai.inspection

import android.graphics.Bitmap
import android.util.Log
import com.roaddefect.ai.ai.DefectDetection
import com.roaddefect.ai.ai.DefectStatus
import com.roaddefect.ai.ai.DefectType
import com.roaddefect.ai.ai.DetectorFrame
import com.roaddefect.ai.ai.RoadDefectDetector
import com.roaddefect.ai.camera.InferenceThrottle
import com.roaddefect.ai.config.ConfigStore
import com.roaddefect.ai.config.DetectionConfig
import com.roaddefect.ai.data.DefectEntity
import com.roaddefect.ai.data.InspectionRepository
import com.roaddefect.ai.data.InspectionStatus
import com.roaddefect.ai.data.RoutePointEntity
import com.roaddefect.ai.location.DistanceAccumulator
import com.roaddefect.ai.location.LocationAssessor
import com.roaddefect.ai.location.LocationFix
import com.roaddefect.ai.location.LocationQuality
import com.roaddefect.ai.location.LocationSource
import com.roaddefect.ai.tracking.DefectTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Orchestrates one inspection: detector lifecycle, frame -> detection -> tracking -> persistence,
 * GPS stamping, distance/route, pause/resume/stop. Owned by the Application so it survives
 * Activity recreation (rotation) and is independent of any UI.
 *
 * Threading:
 *  - [shouldAnalyse] / [processFrame] run on the single CameraX analysis thread.
 *  - start/pause/resume/stop are called from the UI thread; heavy work is moved to [scope] (IO).
 *  - [frameLock] guarantees the detector is never released while a frame is being processed.
 *  - Per-defect DB inserts use runBlocking on the analysis thread (a few ms, only when a defect is
 *    recorded) so Confirmed -> Improved ordering and the track->record map stay simple and correct.
 */
class InspectionController(
    private val repository: InspectionRepository,
    private val location: LocationSource,
    private val configStore: ConfigStore,
    private val detectorFactory: (forceMock: Boolean) -> RoadDefectDetector,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(InspectionUiState())
    val state: StateFlow<InspectionUiState> = _state.asStateFlow()

    /** Set when an inspection finished and saved; the UI navigates to its summary then calls [consumeCompleted]. */
    private val _completedId = MutableStateFlow<Long?>(null)
    val completedId: StateFlow<Long?> = _completedId.asStateFlow()

    @Volatile private var config: DetectionConfig = configStore.load()
    private val tracker = DefectTracker { config }
    private val distance = DistanceAccumulator { config }
    private val frameLock = ReentrantLock()

    @Volatile private var phase = Phase.IDLE
    @Volatile private var detector: RoadDefectDetector? = null
    private val throttle = InferenceThrottle { config.inferenceFps }
    @Volatile private var pendingReset = Reset.NONE
    /**
     * Bumped whenever an inspection pauses or stops so a frame still being processed when that
     * happened is discarded instead of being tracked/recorded late. Written under [frameLock]-free
     * volatile access, read again after the (slow) inference call.
     */
    @Volatile private var sessionEpoch = 0
    @Volatile private var inspectionId = 0L
    @Volatile private var startMs = 0L
    @Volatile private var pausedMs = 0L
    @Volatile private var pauseStartedMs = 0L
    private var lastRouteMs = 0L
    private var job: Job? = null

    /** trackId -> (defect row id, image file name). Touched only under [frameLock]. */
    private val trackToDefect = HashMap<Long, Pair<Long, String>>()

    /** Tracker maintenance the analysis thread must apply before its next frame. */
    private enum class Reset { NONE, KEEP_COOLDOWN }

    // ---------------------------------------------------------------- lifecycle

    fun start() {
        synchronized(this) {
            if (phase != Phase.IDLE) return
            phase = Phase.STARTING
        }
        _state.value = InspectionUiState(phase = Phase.STARTING)
        scope.launch {
            config = configStore.load()
            val det = detectorFactory(configStore.forceMock)
            try {
                det.initialize()
            } catch (t: Throwable) {
                // Never silently fall back to a different detector: fail visibly.
                Log.e(TAG, "Detector failed to initialise", t)
                runCatching { det.release() }
                phase = Phase.IDLE
                _state.value = InspectionUiState(message = "Could not load the detection model: ${t.message}")
                return@launch
            }
            val gpsOk = location.start()
            val now = clock()
            val id = try {
                repository.startInspection(now, det.modelVersion, det.isMock, config.minConfidence)
            } catch (t: Throwable) {
                Log.e(TAG, "Could not create inspection", t)
                runCatching { det.release() }
                location.stop()
                phase = Phase.IDLE
                _state.value = InspectionUiState(message = "Could not start inspection: storage error.")
                return@launch
            }

            frameLock.withLock {
                detector = det
                tracker.reset()
                trackToDefect.clear()
            }
            pendingReset = Reset.NONE
            distance.breakSegment()
            inspectionId = id
            startMs = now
            pausedMs = 0
            lastRouteMs = 0
            throttle.reset()
            _state.value = InspectionUiState(
                phase = Phase.ACTIVE, inspectionId = id, modelVersion = det.modelVersion,
                isMock = det.isMock, locationPermissionMissing = !gpsOk,
            )
            // phase and job are published together so stop() can never observe ACTIVE with no job.
            synchronized(this) {
                job = scope.launch {
                    launch { while (isActive) { delay(1000); onTick() } }
                    launch { location.fix.filterNotNull().collect { onFix(it) } }
                    launch {
                        repository.observeCounts(id).collect { rows ->
                            val map = rows.mapNotNull { r -> DefectType.fromName(r.type)?.let { it to r.count } }.toMap()
                            _state.update { it.copy(counts = map) }
                        }
                    }
                }
                phase = Phase.ACTIVE
            }
        }
    }

    fun pause(auto: Boolean = false) {
        if (!enterPaused(auto)) return
        val id = inspectionId
        scope.launch { repository.setStatus(id, InspectionStatus.PAUSED) }
    }

    fun resume() {
        synchronized(this) {
            if (phase != Phase.PAUSED) return
            pausedMs += clock() - pauseStartedMs
            distance.breakSegment()
            pendingReset = Reset.KEEP_COOLDOWN
            phase = Phase.ACTIVE
        }
        _state.update { it.copy(phase = Phase.ACTIVE, message = null, cameraError = null) }
        val id = inspectionId
        scope.launch { repository.setStatus(id, InspectionStatus.ACTIVE) }
    }

    fun stop() {
        synchronized(this) {
            if (phase != Phase.ACTIVE && phase != Phase.PAUSED) return
            if (phase == Phase.PAUSED) pausedMs += clock() - pauseStartedMs
            phase = Phase.STOPPING
            sessionEpoch++
        }
        _state.update { it.copy(phase = Phase.STOPPING, overlay = emptyList()) }
        scope.launch {
            job?.cancelAndJoin()
            frameLock.withLock {
                runCatching { detector?.release() }
                detector = null
                trackToDefect.clear()
            }
            location.stop()
            val id = inspectionId
            repository.finish(id, clock(), distance.totalM, pausedMs)
            phase = Phase.IDLE
            _state.value = InspectionUiState()
            _completedId.value = id
        }
    }

    fun consumeCompleted() { _completedId.value = null }
    fun clearMessage() { _state.update { it.copy(message = null) } }

    /**
     * The camera failed to bind or vanished. Recording without a preview would silently produce an
     * empty inspection, so the inspection is paused and the reason is shown; Resume clears the error
     * and re-creates the preview (which retries the bind).
     */
    fun reportCameraError(message: String?) {
        _state.update { it.copy(cameraError = message) }
        if (enterPaused(auto = false)) {
            _state.update { it.copy(message = message ?: "Camera stopped unexpectedly.") }
            val id = inspectionId
            scope.launch { repository.setStatus(id, InspectionStatus.PAUSED) }
        }
    }

    /** @return true when this call moved the inspection from ACTIVE to PAUSED. */
    private fun enterPaused(auto: Boolean): Boolean {
        synchronized(this) {
            if (phase != Phase.ACTIVE) return false
            phase = Phase.PAUSED
            pauseStartedMs = clock()
            sessionEpoch++
        }
        _state.update {
            it.copy(
                phase = Phase.PAUSED, overlay = emptyList(),
                message = if (auto) "Inspection paused because the app left the screen. Resume when ready." else it.message,
            )
        }
        return true
    }

    // ---------------------------------------------------------------- frame pipeline

    /** Cheap gate called before the (expensive) bitmap conversion. */
    fun shouldAnalyse(nowMs: Long): Boolean {
        if (phase != Phase.ACTIVE) return false
        return throttle.shouldAnalyse(nowMs)
    }

    /** Longest side frames are scaled to before analysis (bounds memory; also the stored image size). */
    fun frameMaxSidePx(): Int = config.imageMaxSidePx

    /** [bitmap] is upright and owned by the caller (recycled by the caller afterwards). */
    fun processFrame(bitmap: Bitmap, nowMs: Long) {
        frameLock.withLock {
            val det = detector
            if (phase != Phase.ACTIVE || det == null) return@withLock
            val epoch = sessionEpoch
            when (pendingReset) {
                Reset.KEEP_COOLDOWN -> { tracker.reset(preserveCooldown = true); pendingReset = Reset.NONE }
                Reset.NONE -> Unit
            }
            val cfg = config

            val t0 = System.nanoTime()
            val detections: List<DefectDetection> = try {
                det.detect(DetectorFrame(bitmap, nowMs))
            } catch (t: Throwable) {
                Log.e(TAG, "Detector threw; frame skipped", t)
                emptyList()
            }
            val inferenceMs = (System.nanoTime() - t0) / 1_000_000

            // The inspection may have paused/stopped while inference ran: drop the result rather
            // than tracking or recording a defect from a frame the user has already paused.
            if (epoch != sessionEpoch || phase != Phase.ACTIVE) {
                _state.update { it.copy(inferenceMs = inferenceMs, overlay = emptyList()) }
                return@withLock
            }

            val fix = location.fix.value
            val events = tracker.update(detections, nowMs, fix)
            val recordedTypes = events.filterIsInstance<DefectTracker.Event.Confirmed>().map { it.detection.type }.toSet()

            val overlay = detections
                .filter { it.isWellFormed() && it.confidence >= cfg.minConfidence }
                .map { OverlayBox(it.type, it.confidence, it.boundingBox, it.type in recordedTypes) }
            _state.update { it.copy(overlay = overlay, inferenceMs = inferenceMs) }

            for (event in events) {
                try {
                    persist(event, bitmap, nowMs, fix, cfg)
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to persist defect event", t)
                }
            }
        }
    }

    private fun persist(event: DefectTracker.Event, bitmap: Bitmap, nowMs: Long, fix: LocationFix?, cfg: DetectionConfig) {
        val d = event.detection
        when (event) {
            is DefectTracker.Event.Confirmed -> {
                val quality = LocationAssessor.assess(fix, nowMs, cfg)
                val hasPosition = quality != LocationQuality.NONE && fix != null
                val uuid = UUID.randomUUID().toString()
                val path = repository.images.save(bitmap, inspectionId, uuid, cfg.imageMaxSidePx, cfg.imageJpegQuality)
                val entity = DefectEntity(
                    uuid = uuid,
                    inspectionId = inspectionId,
                    type = d.type.name,
                    confidence = d.confidence,
                    latitude = if (hasPosition) fix!!.latitude else null,
                    longitude = if (hasPosition) fix!!.longitude else null,
                    gpsAccuracy = if (hasPosition && fix!!.accuracyM.isFinite()) fix.accuracyM else null,
                    locationQuality = quality.name,
                    speedMps = if (hasPosition) fix!!.speedMps else null,
                    timestamp = nowMs,
                    imagePath = path,
                    status = DefectStatus.AI_DETECTED.name,
                )
                val id = runBlocking { repository.addDefect(entity) }
                trackToDefect[event.trackId] = id to uuid
            }
            is DefectTracker.Event.Improved -> {
                val (defectId, uuid) = trackToDefect[event.trackId] ?: return
                val path = repository.images.save(bitmap, inspectionId, uuid, cfg.imageMaxSidePx, cfg.imageJpegQuality) ?: return
                runBlocking { repository.updateBestFrame(defectId, d.confidence, path) }
            }
        }
    }

    // ---------------------------------------------------------------- status ticks / GPS

    private fun elapsed(now: Long): Long {
        val currentPause = if (phase == Phase.PAUSED) now - pauseStartedMs else 0L
        return (now - startMs - pausedMs - currentPause).coerceAtLeast(0)
    }

    private fun refreshStatus() {
        val now = clock()
        val fix = location.fix.value
        val quality = LocationAssessor.assess(fix, now, config)
        _state.update {
            it.copy(
                elapsedMs = elapsed(now),
                distanceM = distance.totalM,
                speedMps = if (quality == LocationQuality.NONE) null else fix?.speedMps,
                gpsQuality = quality,
                gpsAccuracyM = if (quality == LocationQuality.NONE) null else fix?.accuracyM,
            )
        }
    }

    private var tickCount = 0

    private suspend fun onTick() {
        refreshStatus()
        if (++tickCount % PROGRESS_SAVE_EVERY_TICKS == 0) {
            // Persist progress so an app kill/crash keeps distance and paused time.
            runCatching { repository.saveProgress(inspectionId, distance.totalM, pausedMs) }
        }
    }

    private fun onFix(fix: LocationFix) {
        if (phase != Phase.ACTIVE) return
        distance.onFix(fix)
        val now = clock()
        if (LocationAssessor.assess(fix, now, config) == LocationQuality.GOOD && now - lastRouteMs >= ROUTE_INTERVAL_MS) {
            lastRouteMs = now
            val id = inspectionId
            scope.launch {
                runCatching { repository.addRoutePoint(RoutePointEntity(inspectionId = id, latitude = fix.latitude, longitude = fix.longitude, accuracy = fix.accuracyM, timestamp = now)) }
            }
        }
        refreshStatus()
    }

    private companion object {
        const val TAG = "InspectionController"
        const val PROGRESS_SAVE_EVERY_TICKS = 15
        const val ROUTE_INTERVAL_MS = 3000L
    }
}
