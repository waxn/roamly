package com.roamly.tracking

import androidx.room.*
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

    @Query("DELETE FROM cached_points WHERE synced = 1 AND timestamp < :cutoffMs")
    suspend fun pruneOldSynced(cutoffMs: Long)

    @Query("DELETE FROM cached_points")
    suspend fun deleteAll()
}
