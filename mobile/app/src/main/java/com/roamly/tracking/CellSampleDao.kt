package com.roamly.tracking

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query

/**
 * Mirrors [PointDao], minus the live-count flows.
 *
 * Deliberately no `Flow` here. `SettingsViewModel` is excluded from
 * Activity-scoping precisely because its continuous collectors run against this
 * database in the same process as the capture loop; a second such flow is the
 * regression that exclusion exists to prevent. An unsynced count, where wanted,
 * is the one-shot [unsyncedCount] below.
 */
@Dao
interface CellSampleDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(samples: List<CellSample>)

    @Query("UPDATE cell_samples SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Long>)

    @Query("SELECT * FROM cell_samples WHERE synced = 0 ORDER BY timestamp ASC LIMIT :limit")
    suspend fun getUnsynced(limit: Int = 300): List<CellSample>

    @Query("SELECT COUNT(*) FROM cell_samples WHERE synced = 0")
    suspend fun unsyncedCount(): Int

    @Query("DELETE FROM cell_samples WHERE synced = 1 AND timestamp < :cutoffMs")
    suspend fun pruneOldSynced(cutoffMs: Long)

    @Query("DELETE FROM cell_samples")
    suspend fun deleteAll()
}
