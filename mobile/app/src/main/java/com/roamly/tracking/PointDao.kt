package com.roamly.tracking

import androidx.room3.*
import kotlinx.coroutines.flow.Flow

@Dao
interface PointDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(point: CachedPoint): Long

    @Query("UPDATE cached_points SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Long>)

    @Query("SELECT * FROM cached_points WHERE synced = 0 ORDER BY timestamp ASC LIMIT :limit")
    suspend fun getUnsynced(limit: Int = 500): List<CachedPoint>

    @Query("SELECT COUNT(*) FROM cached_points WHERE synced = 0")
    fun unsyncedCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM cached_points WHERE synced = 0")
    suspend fun unsyncedCount(): Int

    @Query("SELECT COUNT(*) FROM cached_points")
    fun totalCountFlow(): Flow<Int>

    /**
     * Live point count for an in-progress recording — the *trigger* for a UI
     * refresh, not the data itself.
     *
     * A `Flow<List<CachedPoint>>` here would be O(n^2): Room re-emits the whole
     * list on every insert, so a 40-minute ride at 2s would re-read a growing list
     * once per point. Pairing this count with [sinceTail] keeps the cost O(new).
     */
    @Query("SELECT COUNT(*) FROM cached_points WHERE timestamp >= :startMs")
    fun countSinceFlow(startMs: Long): Flow<Int>

    /**
     * Points recorded after [afterId], for folding into a running total.
     *
     * Cursored by `id` rather than `timestamp`: `id` is autoincrement and monotonic
     * for inserts, so it is exact even when two fixes share a millisecond, which a
     * timestamp cursor would either duplicate or skip.
     */
    @Query("SELECT * FROM cached_points WHERE id > :afterId AND timestamp >= :startMs ORDER BY id ASC")
    suspend fun sinceTail(afterId: Long, startMs: Long): List<CachedPoint>

    @Query("DELETE FROM cached_points WHERE synced = 1 AND timestamp < :cutoffMs")
    suspend fun pruneOldSynced(cutoffMs: Long)

    @Query("DELETE FROM cached_points")
    suspend fun deleteAll()
}
