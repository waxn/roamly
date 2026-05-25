package com.roamly.tracker.db

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface PointDao {

    // ── Write ──────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(point: CachedPoint): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(points: List<CachedPoint>)

    // ── Mark synced ────────────────────────────────────────────────────────

    @Query("UPDATE cached_points SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Long>)

    // ── Queries ────────────────────────────────────────────────────────────

    /** All unsynced points, oldest first — used by UploadWorker. */
    @Query("SELECT * FROM cached_points WHERE synced = 0 ORDER BY timestamp ASC LIMIT :limit")
    suspend fun getUnsynced(limit: Int = 500): List<CachedPoint>

    /** Total number of unsynced points (shown in the UI). */
    @Query("SELECT COUNT(*) FROM cached_points WHERE synced = 0")
    fun unsyncedCountLive(): LiveData<Int>

    @Query("SELECT COUNT(*) FROM cached_points WHERE synced = 0")
    suspend fun unsyncedCount(): Int

    /** Total stored points (synced + unsynced). */
    @Query("SELECT COUNT(*) FROM cached_points")
    fun totalCountLive(): LiveData<Int>

    // ── Pruning ────────────────────────────────────────────────────────────

    /**
     * Delete synced points older than [cutoffMs] to keep DB size bounded.
     * Called on app startup and after each successful upload batch.
     */
    @Query("DELETE FROM cached_points WHERE synced = 1 AND timestamp < :cutoffMs")
    suspend fun pruneOldSynced(cutoffMs: Long)

    /** Hard delete everything — used by "Clear all data" in Settings. */
    @Query("DELETE FROM cached_points")
    suspend fun deleteAll()
}
