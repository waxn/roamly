package com.roamly.data.local

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import com.roamly.tracking.SyncedLocation
import com.roamly.tracking.SyncedLocationDao

/**
 * The map's local mirror of server-side history, in its **own** database file.
 *
 * It used to live in `TrackingDatabase` alongside the [com.roamly.tracking.CachedPoint]
 * capture queue, which meant one SQLite file and therefore **one write lock**
 * shared between the map and GPS capture. Every viewport query
 * (`MapViewModel.onViewportChanged` pulls up to `BBOX_LOAD_LIMIT` rows, and
 * `LocationStore.sync()` writes 8000-row `REPLACE` transactions) contended with
 * `LocationTrackingService.savePoint()`'s per-fix insert. Because that insert is
 * on the capture path and the filter's de-dup window is advanced only after it
 * returns, a blocked write did not merely delay a point — the stream fixes
 * queued behind it were then rejected as stale or duplicate, i.e. **lost** — and
 * the resulting missed cycles degraded the capture priority to a coarser,
 * Doppler-less provider. Timing and accuracy fell together, intermittently,
 * depending on whether the user happened to be using the map.
 *
 * Splitting the file makes that structurally impossible: no map query, however
 * large or frequent, can now take a lock the capture loop needs.
 *
 * **`fallbackToDestructiveMigration` belongs here, not on `TrackingDatabase`.**
 * Every row is a rebuildable cache keyed by server id — `LocationStore.sync()`
 * refills it from scratch when it is empty — so dropping it costs one re-sync.
 * The capture queue, by contrast, holds points that exist *nowhere else* until
 * they upload, which is why that database now carries real migrations only.
 */
@Database(entities = [SyncedLocation::class], version = 1, exportSchema = false)
abstract class MapCacheDatabase : RoomDatabase() {
    abstract fun syncedLocationDao(): SyncedLocationDao

    companion object {
        @Volatile private var INSTANCE: MapCacheDatabase? = null

        fun getInstance(context: Context): MapCacheDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder<MapCacheDatabase>(
                    context.applicationContext, "roamly_map_cache.db"
                ).fallbackToDestructiveMigration(true)
                    .build().also { INSTANCE = it }
            }
    }
}
