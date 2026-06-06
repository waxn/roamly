package com.roamly.tracking

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

@Database(entities = [CachedPoint::class, SyncedLocation::class], version = 2, exportSchema = false)
abstract class TrackingDatabase : RoomDatabase() {
    abstract fun pointDao(): PointDao
    abstract fun syncedLocationDao(): SyncedLocationDao

    companion object {
        @Volatile private var INSTANCE: TrackingDatabase? = null

        /**
         * v1 → v2 adds the [SyncedLocation] read store. A real migration (rather
         * than destructive) is used so the [CachedPoint] upload queue — which may
         * hold points not yet pushed to the server — is never wiped.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `synced_locations` (" +
                        "`id` INTEGER NOT NULL, `lat` REAL NOT NULL, `lng` REAL NOT NULL, " +
                        "`timestampMs` INTEGER NOT NULL, `timestampIso` TEXT NOT NULL, " +
                        "`deviceId` TEXT NOT NULL, `altitude` REAL, `accuracy` REAL, " +
                        "`speed` REAL, `battery` REAL, `city` TEXT, `state` TEXT, " +
                        "`country` TEXT, `countryCode` TEXT, PRIMARY KEY(`id`))"
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_synced_locations_timestampMs` " +
                        "ON `synced_locations` (`timestampMs`)"
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_synced_locations_lat_lng` " +
                        "ON `synced_locations` (`lat`, `lng`)"
                )
            }
        }

        fun getInstance(context: Context): TrackingDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder<TrackingDatabase>(
                    context.applicationContext, "roamly_tracking.db"
                ).addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration(true)
                    .build().also { INSTANCE = it }
            }
    }
}
