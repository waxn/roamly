package com.roamly.tracking

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase

@Database(entities = [CachedPoint::class], version = 1, exportSchema = false)
abstract class TrackingDatabase : RoomDatabase() {
    abstract fun pointDao(): PointDao

    companion object {
        @Volatile private var INSTANCE: TrackingDatabase? = null

        fun getInstance(context: Context): TrackingDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder<TrackingDatabase>(
                    context.applicationContext, "roamly_tracking.db"
                ).fallbackToDestructiveMigration(true).build().also { INSTANCE = it }
            }
    }
}
