package com.roaddefect.ai.data

import com.roaddefect.ai.ai.DefectStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** Data layer entry point. Knows nothing about the AI layer, camera or UI. */
class InspectionRepository(
    private val db: RoadDefectDatabase,
    val images: ImageStore,
) {
    private val inspections get() = db.inspectionDao()
    private val defects get() = db.defectDao()

    suspend fun recoverInterrupted() = inspections.markUnfinishedAsInterrupted()

    suspend fun startInspection(startTime: Long, modelVersion: String, modelIsMock: Boolean, minConfidence: Float): Long =
        inspections.insert(
            InspectionEntity(
                startTime = startTime, modelVersion = modelVersion,
                modelIsMock = modelIsMock, minConfidence = minConfidence,
            )
        )

    suspend fun setStatus(id: Long, status: InspectionStatus) = inspections.setStatus(id, status.name)

    suspend fun saveProgress(id: Long, distanceM: Double, pausedMs: Long) = inspections.updateProgress(id, distanceM, pausedMs)

    suspend fun finish(id: Long, endTime: Long, distanceM: Double, pausedMs: Long) {
        val current = inspections.get(id) ?: return
        inspections.update(
            current.copy(
                endTime = endTime, distanceMeters = distanceM, pausedMs = pausedMs,
                status = InspectionStatus.COMPLETED.name, syncState = SyncState.PENDING.name,
            )
        )
    }

    suspend fun addDefect(defect: DefectEntity): Long = defects.insert(defect)

    suspend fun updateBestFrame(defectId: Long, confidence: Float, imagePath: String?) =
        defects.updateBestFrame(defectId, confidence, imagePath)

    suspend fun setDefectStatus(defectId: Long, status: DefectStatus) = defects.setStatus(defectId, status.name)

    suspend fun addRoutePoint(point: RoutePointEntity) = db.routeDao().insert(point)
    suspend fun getRoute(inspectionId: Long) = db.routeDao().getForInspection(inspectionId)

    suspend fun getInspection(id: Long) = inspections.get(id)
    suspend fun getDefects(inspectionId: Long) = defects.getForInspection(inspectionId)

    fun observeInspection(id: Long): Flow<InspectionEntity?> = inspections.observe(id)
    fun observeHistory(): Flow<List<InspectionWithCount>> = inspections.observeAllWithCounts()
    fun observeDefects(inspectionId: Long): Flow<List<DefectEntity>> = defects.observeForInspection(inspectionId)
    fun observeCounts(inspectionId: Long): Flow<List<TypeCount>> = defects.observeCounts(inspectionId)

    /** Runs on IO: deleting an inspection can mean removing hundreds of image files. */
    suspend fun deleteInspection(id: Long) = withContext(Dispatchers.IO) {
        inspections.delete(id) // defects cascade
        images.deleteInspectionImages(id)
    }
}
