package com.roaddefect.ai.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

enum class InspectionStatus { ACTIVE, PAUSED, COMPLETED, INTERRUPTED }

enum class SyncState { LOCAL_ONLY, PENDING, SYNCED }

/**
 * `id` is the local sequence shown as INS-000001. `uuid` is a globally unique id reserved for
 * future cloud sync; `syncState` marks rows that still need uploading.
 */
@Entity(tableName = "inspections")
data class InspectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String = UUID.randomUUID().toString(),
    val startTime: Long,
    val endTime: Long? = null,
    val distanceMeters: Double = 0.0,
    val pausedMs: Long = 0,
    val status: String = InspectionStatus.ACTIVE.name,
    /** Model identifier, e.g. "RoadDefectAI-v1.0", for comparing model versions later. */
    val modelVersion: String,
    val modelIsMock: Boolean,
    val minConfidence: Float,
    val syncState: String = SyncState.LOCAL_ONLY.name,
)

@Entity(
    tableName = "defects",
    foreignKeys = [
        ForeignKey(
            entity = InspectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["inspectionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("inspectionId")],
)
data class DefectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String = UUID.randomUUID().toString(),
    val inspectionId: Long,
    val type: String,
    val confidence: Float,
    /** Null when there was no usable GPS fix at detection time (locationQuality = NONE). */
    val latitude: Double?,
    val longitude: Double?,
    val gpsAccuracy: Float?,
    /** GOOD, LOW or NONE. Low-accuracy locations are flagged, never silently trusted. */
    val locationQuality: String,
    val speedMps: Float?,
    val timestamp: Long,
    /** Path relative to the app's files dir, so the DB survives storage-path changes. */
    val imagePath: String?,
    val status: String,
    val syncState: String = SyncState.LOCAL_ONLY.name,
)

data class TypeCount(val type: String, val count: Int)

data class InspectionWithCount(
    val id: Long,
    val startTime: Long,
    val endTime: Long?,
    val distanceMeters: Double,
    val pausedMs: Long,
    val status: String,
    val modelVersion: String,
    val modelIsMock: Boolean,
    val defectCount: Int,
)

/** One breadcrumb of the driven route, recorded only while GPS accuracy is good. */
@Entity(
    tableName = "route_points",
    foreignKeys = [
        ForeignKey(
            entity = InspectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["inspectionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("inspectionId")],
)
data class RoutePointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val inspectionId: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val timestamp: Long,
)
