package com.roamly.tracking

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [CachedPoint::class], version = 1, exportSchema = false)
abstract class TrackingDatabase : RoomDatabase() {
    abstract fun pointDao(): PointDao

    companion object {
        @Volatile private var INSTANCE: TrackingDatabase? = null

        fun getInstance(context: Context): TrackingDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext, TrackingDatabase::class.java, "roamly_tracking.db"
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
    }
}
