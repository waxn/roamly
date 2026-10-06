package com.roamly.tracking

import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.PrimaryKey
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * One raw fix of a recorded activity, kept privately until the whole track has
 * uploaded at Stop.
 *
 * Deliberately **not** a [CachedPoint]. The all-day queue is shaped by all-day
 * rules — a de-dup window, dwell substitution, a 100 m accuracy gate, upload
 * every minute — and a ride recorded through it inherited every one of them.
 * This table holds only what [ActivityFilter] accepted, at 1 Hz, and is drained
 * by [ActivitySaveWorker] in one go. The server mirrors a thinned, cleaned copy
 * into normal history, so nothing here ever goes through `cached_points`.
 */
@Entity(
    tableName = "activity_points",
    indices = [Index(value = ["activityId", "id"])]
)
data class ActivityPoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val activityId: String,
    /** GPS fix time, Unix millis. */
    val t: Long,
    val lat: Double,
    val lon: Double,
    val alt: Double?,
    val acc: Float?,
    val spd: Float?,
)

@Dao
interface ActivityPointDao {
    @Insert
    suspend fun insertAll(points: List<ActivityPoint>)

    /**
     * Trigger for the live screen — a count, not the rows, so the UI pulls only
     * what is new via [after] (the same O(new) pattern PointDao uses).
     */
    @Query("SELECT COUNT(*) FROM activity_points WHERE activityId = :activityId")
    fun countFlow(activityId: String): Flow<Int>

    @Query("SELECT * FROM activity_points WHERE activityId = :activityId AND id > :afterId ORDER BY id ASC LIMIT :limit")
    suspend fun after(activityId: String, afterId: Long, limit: Int = 5000): List<ActivityPoint>

    @Query("SELECT COUNT(*) FROM activity_points WHERE activityId = :activityId")
    suspend fun count(activityId: String): Int

    @Query("DELETE FROM activity_points WHERE activityId = :activityId")
    suspend fun deleteActivity(activityId: String)

    /** Activity ids with points still on the phone, for orphan recovery. */
    @Query("SELECT DISTINCT activityId FROM activity_points")
    suspend fun activityIds(): List<String>
}
