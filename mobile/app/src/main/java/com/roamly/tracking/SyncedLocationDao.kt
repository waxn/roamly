package com.roamly.tracking

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query

@Dao
interface SyncedLocationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(points: List<SyncedLocation>)

    @Query("SELECT COUNT(*) FROM synced_locations")
    suspend fun count(): Int

    /** Newest point we hold — the cursor for incremental sync. */
    @Query("SELECT MAX(timestampMs) FROM synced_locations")
    suspend fun maxTimestampMs(): Long?

    /** Points within a time window, oldest-first. */
    @Query("SELECT * FROM synced_locations WHERE timestampMs >= :sinceMs ORDER BY timestampMs ASC")
    suspend fun inPeriod(sinceMs: Long): List<SyncedLocation>

    @Query("SELECT COUNT(*) FROM synced_locations WHERE timestampMs >= :sinceMs")
    suspend fun countInPeriod(sinceMs: Long): Int

    /**
     * Decimated overview of a period: every [stride]-th point. Server ids climb
     * roughly with time, so `id % stride` is a cheap, evenly-spread sample that
     * keeps the heatmap fast without loading the whole history into memory.
     */
    @Query(
        "SELECT * FROM synced_locations WHERE timestampMs >= :sinceMs AND (id % :stride) = 0 " +
            "ORDER BY timestampMs ASC"
    )
    suspend fun inPeriodDecimated(sinceMs: Long, stride: Int): List<SyncedLocation>

    /**
     * Movement-aware overview: keep every point the device logged as moving
     * (speed >= [moveThresh] — the tracker floors stationary jitter to exactly 0,
     * so any positive speed is real motion) plus a [stride]-decimated sample of the
     * rest. This keeps walks and drives dense while still thinning the thousands of
     * points piled up at home, so a beach stroll no longer gaps out at the same
     * harsh stride as the place you sat still in.
     */
    @Query(
        "SELECT * FROM synced_locations WHERE timestampMs >= :sinceMs AND " +
            "(speed >= :moveThresh OR (id % :stride) = 0) ORDER BY timestampMs ASC"
    )
    suspend fun inPeriodMovementAware(sinceMs: Long, stride: Int, moveThresh: Double): List<SyncedLocation>

    /** Full-resolution points inside a viewport, within a time window. */
    @Query(
        "SELECT * FROM synced_locations WHERE timestampMs >= :sinceMs AND " +
            "lat BETWEEN :minLat AND :maxLat AND lng BETWEEN :minLng AND :maxLng " +
            "ORDER BY timestampMs ASC LIMIT :limit"
    )
    suspend fun inBbox(
        minLat: Double, maxLat: Double, minLng: Double, maxLng: Double,
        sinceMs: Long, limit: Int,
    ): List<SyncedLocation>

    /** All-time points inside a small box — powers "have I been here?". */
    @Query(
        "SELECT * FROM synced_locations WHERE " +
            "lat BETWEEN :minLat AND :maxLat AND lng BETWEEN :minLng AND :maxLng " +
            "ORDER BY timestampMs ASC"
    )
    suspend fun allTimeInBbox(
        minLat: Double, maxLat: Double, minLng: Double, maxLng: Double,
    ): List<SyncedLocation>

    /** Points within an explicit ms window — for custom date range and single-day views. */
    @Query(
        "SELECT * FROM synced_locations WHERE timestampMs >= :startMs AND timestampMs < :endMs " +
            "ORDER BY timestampMs ASC LIMIT :limit"
    )
    suspend fun inTimeRange(startMs: Long, endMs: Long, limit: Int): List<SyncedLocation>

    @Query(
        "SELECT COUNT(*) FROM synced_locations WHERE timestampMs >= :startMs AND timestampMs < :endMs"
    )
    suspend fun countInTimeRange(startMs: Long, endMs: Long): Int

    /** Remove a single point locally after it's deleted server-side, so the map
     *  drops it immediately and an incremental sync never re-adds it. */
    @Query("DELETE FROM synced_locations WHERE id = :id")
    suspend fun deleteById(id: Int)

    @Query("DELETE FROM synced_locations")
    suspend fun deleteAll()
}
