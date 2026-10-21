package com.roaddefect.ai.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface InspectionDao {
    @Insert suspend fun insert(inspection: InspectionEntity): Long
    @Update suspend fun update(inspection: InspectionEntity)

    @Query("SELECT * FROM inspections WHERE id = :id")
    suspend fun get(id: Long): InspectionEntity?

    @Query("SELECT * FROM inspections WHERE id = :id")
    fun observe(id: Long): Flow<InspectionEntity?>

    @Query(
        """SELECT i.id, i.startTime, i.endTime, i.distanceMeters, i.pausedMs, i.status, i.modelVersion, i.modelIsMock,
           (SELECT COUNT(*) FROM defects d WHERE d.inspectionId = i.id AND d.status != 'REJECTED') AS defectCount
           FROM inspections i ORDER BY i.startTime DESC"""
    )
    fun observeAllWithCounts(): Flow<List<InspectionWithCount>>

    /**
     * Never downgrades a finished inspection: pause/resume writes are fired without ordering
     * guarantees, so one arriving after finish must not flip a COMPLETED row back to ACTIVE.
     */
    @Query("UPDATE inspections SET status = :status WHERE id = :id AND status NOT IN ('COMPLETED','INTERRUPTED')")
    suspend fun setStatus(id: Long, status: String)

    /** Progress saves are ignored once the inspection is finished, so they cannot resurrect stale values. */
    @Query("UPDATE inspections SET distanceMeters = :distance, pausedMs = :pausedMs WHERE id = :id AND status IN ('ACTIVE','PAUSED')")
    suspend fun updateProgress(id: Long, distance: Double, pausedMs: Long)

    /** Crash/kill recovery: anything still ACTIVE/PAUSED at app start was interrupted. Data is kept. */
    @Query("UPDATE inspections SET status = 'INTERRUPTED' WHERE status IN ('ACTIVE','PAUSED')")
    suspend fun markUnfinishedAsInterrupted()

    @Query("DELETE FROM inspections WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface DefectDao {
    @Insert suspend fun insert(defect: DefectEntity): Long

    @Query("SELECT * FROM defects WHERE id = :id")
    suspend fun get(id: Long): DefectEntity?

    @Query("SELECT * FROM defects WHERE inspectionId = :inspectionId ORDER BY timestamp ASC")
    fun observeForInspection(inspectionId: Long): Flow<List<DefectEntity>>

    @Query("SELECT * FROM defects WHERE inspectionId = :inspectionId ORDER BY timestamp ASC")
    suspend fun getForInspection(inspectionId: Long): List<DefectEntity>

    @Query("SELECT type, COUNT(*) AS count FROM defects WHERE inspectionId = :inspectionId AND status != 'REJECTED' GROUP BY type")
    fun observeCounts(inspectionId: Long): Flow<List<TypeCount>>

    @Query("UPDATE defects SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: String)

    @Query("UPDATE defects SET confidence = :confidence, imagePath = :imagePath WHERE id = :id")
    suspend fun updateBestFrame(id: Long, confidence: Float, imagePath: String?)
}

@Dao
interface RoutePointDao {
    @Insert suspend fun insert(point: RoutePointEntity)

    @Query("SELECT * FROM route_points WHERE inspectionId = :inspectionId ORDER BY timestamp ASC")
    suspend fun getForInspection(inspectionId: Long): List<RoutePointEntity>
}
