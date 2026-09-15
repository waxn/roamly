package com.roamly.tracking

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * The capture queue, and **only** the capture queue.
 *
 * [CachedPoint] rows are GPS fixes that exist nowhere else until they upload, so
 * this database never gets a destructive fallback — every version step has a real
 * migration. (`synced_locations`, the map's rebuildable mirror, used to live here
 * too; it moved to [com.roamly.data.local.MapCacheDatabase] in v3 so that map
 * queries can no longer take the write lock GPS capture needs. See that file.)
 */
@Database(entities = [CachedPoint::class, CellSample::class], version = 4, exportSchema = false)
abstract class TrackingDatabase : RoomDatabase() {
    abstract fun pointDao(): PointDao
    abstract fun cellDao(): CellSampleDao

    companion object {
        @Volatile private var INSTANCE: TrackingDatabase? = null

        /**
         * v1 → v2 added the SyncedLocation read store. Kept verbatim even though
         * v3 drops that table again: a device still on v1 has to be able to reach
         * v3, and Room composes the steps rather than skipping to the newest.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override suspend fun migrate(connection: SQLiteConnection) {
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

        /**
         * v2 → v3 evicts the map mirror from this file so the map and GPS capture
         * stop sharing a write lock.
         *
         * Dropping rather than copying is deliberate: every row is keyed by server
         * id and `LocationStore.sync()` rebuilds the whole table from the server
         * when it finds it empty, so the only cost is one re-sync — into the new
         * file, where it can no longer stall a fix. SQLite drops a table's indices
         * with the table, so there is nothing else to clean up.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL("DROP TABLE IF EXISTS `synced_locations`")
            }
        }

        /**
         * v3 → v4 adds the cell-coverage capture queue.
         *
         * Additive: `cached_points` is untouched, so an in-flight backlog of
         * un-uploaded fixes survives the upgrade. That is the whole reason this
         * file has no destructive fallback, and this is the first migration
         * written since that guarantee was documented — so the column list,
         * nullability and index names below must match what Room generates for
         * [CellSample] exactly, or Room aborts on the identity-hash check.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `cell_samples` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`clientId` TEXT NOT NULL, " +
                        "`timestamp` INTEGER NOT NULL, " +
                        "`latitude` REAL NOT NULL, " +
                        "`longitude` REAL NOT NULL, " +
                        "`accuracy` REAL, " +
                        "`rat` TEXT NOT NULL, " +
                        "`role` TEXT NOT NULL, " +
                        "`simSlot` INTEGER NOT NULL, " +
                        "`carrier` TEXT, `mcc` TEXT, `mnc` TEXT, " +
                        "`tac` INTEGER, `cid` INTEGER, `pci` INTEGER, " +
                        "`earfcn` INTEGER, `band` INTEGER, " +
                        "`dbm` INTEGER, `asu` INTEGER, `level` INTEGER, `rsrq` INTEGER, " +
                        "`synced` INTEGER NOT NULL DEFAULT 0)"
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_cell_samples_synced_timestamp` " +
                        "ON `cell_samples` (`synced`, `timestamp`)"
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_cell_samples_timestamp` " +
                        "ON `cell_samples` (`timestamp`)"
                )
            }
        }

        fun getInstance(context: Context): TrackingDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder<TrackingDatabase>(
                    context.applicationContext, "roamly_tracking.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    // NO fallbackToDestructiveMigration. It was set here, directly
                    // contradicting the KDoc above it, and would silently wipe
                    // un-uploaded fixes the first time a version bump shipped
                    // without a matching migration. A crash on an unexpected
                    // schema is recoverable; a lost queue is not.
                    .build().also { INSTANCE = it }
            }
    }
}
