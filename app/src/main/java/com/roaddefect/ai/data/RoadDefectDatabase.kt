package com.roaddefect.ai.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [InspectionEntity::class, DefectEntity::class, RoutePointEntity::class], version = 1, exportSchema = false)
abstract class RoadDefectDatabase : RoomDatabase() {
    abstract fun inspectionDao(): InspectionDao
    abstract fun defectDao(): DefectDao
    abstract fun routeDao(): RoutePointDao

    companion object {
        @Volatile private var instance: RoadDefectDatabase? = null

        fun get(context: Context): RoadDefectDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, RoadDefectDatabase::class.java, "roaddefect.db")
                .build().also { instance = it }
        }
    }
}
