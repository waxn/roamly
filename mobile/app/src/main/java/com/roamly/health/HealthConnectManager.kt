package com.roamly.health

import android.content.Context
import android.content.Intent
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.changes.Change
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.response.ChangesResponse
import androidx.health.connect.client.time.TimeRangeFilter
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.reflect.KClass

/**
 * Read-only bridge to Health Connect. Roamly never writes back.
 *
 * Availability, permissions and reads all live here so the worker and the UI
 * share one view of them.
 */
@Singleton
class HealthConnectManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** What the UI has to tell the user, and which of them are recoverable. */
    enum class Availability {
        /** Ready to use. */
        AVAILABLE,

        /** Health Connect is installed but too old — offer the Play Store link. */
        UPDATE_REQUIRED,

        /** No Health Connect on this device. On a phone without Play Store this
         *  is a genuine dead end, so the UI says so rather than offering a retry
         *  button that cannot work. */
        UNAVAILABLE,
    }

    /** The metric record types synced automatically. */
    val syncedRecordTypes: Set<KClass<out Record>> = setOf(
        StepsRecord::class,
        DistanceRecord::class,
        ActiveCaloriesBurnedRecord::class,
        TotalCaloriesBurnedRecord::class,
    )

    /**
     * The reads Roamly cannot work without. Exercise is included because the
     * workout browser needs it — but exercise sessions are still only ever
     * uploaded when the user taps Import.
     */
    val requiredPermissions: Set<String> = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
    )

    /**
     * Read further back than 30 days. Requested separately, with its own
     * explanation: without it Health Connect silently returns nothing older
     * than a month, which looks like a broken backfill rather than a missing
     * permission.
     */
    val historyPermission: String = HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY

    /**
     * Read while the app is backgrounded. Also requested separately — declining
     * it should leave a feature that syncs when you open the app, not a worker
     * that fails forever.
     */
    val backgroundPermission: String = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND

    fun availability(): Availability = when (HealthConnectClient.getSdkStatus(context)) {
        HealthConnectClient.SDK_AVAILABLE -> Availability.AVAILABLE
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> Availability.UPDATE_REQUIRED
        else -> Availability.UNAVAILABLE
    }

    /**
     * Never construct the client unless availability() is AVAILABLE — getOrCreate
     * throws otherwise.
     */
    val client: HealthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    fun permissionContract() = PermissionController.createRequestPermissionResultContract()

    /** Deep link to Health Connect's own settings, for the permanently-denied case. */
    fun settingsIntent(): Intent = Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)

    suspend fun grantedPermissions(): Set<String> =
        if (availability() != Availability.AVAILABLE) emptySet()
        else client.permissionController.getGrantedPermissions()

    suspend fun hasRequiredPermissions(): Boolean =
        grantedPermissions().containsAll(requiredPermissions)

    /** Read every record of one type in a window, following Health Connect's paging. */
    suspend fun <T : Record> readRange(type: KClass<T>, start: Instant, end: Instant): List<T> {
        val out = mutableListOf<T>()
        var token: String? = null
        do {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = type,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    pageToken = token,
                )
            )
            out += response.records
            token = response.pageToken
        } while (token != null)
        return out
    }

    /**
     * Every synced metric record in a window.
     *
     * Iterates the concrete types explicitly rather than looping over
     * [syncedRecordTypes]: ReadRecordsRequest is generic in the record type, and
     * a `KClass<out Record>` from that set is a projection the compiler cannot
     * bind to it.
     */
    suspend fun readAllMetrics(start: Instant, end: Instant): List<Record> = buildList {
        addAll(readRange(StepsRecord::class, start, end))
        addAll(readRange(DistanceRecord::class, start, end))
        addAll(readRange(ActiveCaloriesBurnedRecord::class, start, end))
        addAll(readRange(TotalCaloriesBurnedRecord::class, start, end))
    }

    /**
     * Mint a cursor for incremental sync.
     *
     * ExerciseSessionRecord is deliberately excluded: workouts are imported by
     * hand, so a change stream for them would only build a queue of upserts the
     * user never asked for.
     */
    suspend fun changesToken(): String =
        client.getChangesToken(ChangesTokenRequest(recordTypes = syncedRecordTypes))

    suspend fun changes(token: String): ChangesResponse = client.getChanges(token)

    suspend fun exerciseSessions(start: Instant, end: Instant): List<ExerciseSessionRecord> =
        readRange(ExerciseSessionRecord::class, start, end)

    /**
     * Totals for one exercise session, via Health Connect's own aggregate() —
     * which resolves overlapping records across source apps, unlike a raw read.
     *
     * Called lazily as a row scrolls into view: aggregating ninety days of
     * sessions up front is slow enough to be noticeable.
     */
    suspend fun sessionTotals(start: Instant, end: Instant): SessionTotals {
        return try {
            val response = client.aggregate(
                AggregateRequest(
                    metrics = setOf(
                        StepsRecord.COUNT_TOTAL,
                        DistanceRecord.DISTANCE_TOTAL,
                        ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL,
                    ),
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                )
            )
            SessionTotals(
                steps = response[StepsRecord.COUNT_TOTAL]?.toInt(),
                distanceM = response[DistanceRecord.DISTANCE_TOTAL]?.inMeters,
                caloriesKcal = response[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories,
            )
        } catch (e: Exception) {
            SessionTotals(null, null, null)
        }
    }

    data class SessionTotals(
        val steps: Int?,
        val distanceM: Double?,
        val caloriesKcal: Double?,
    )
}

/** True when this change is a deletion rather than an upsert. */
fun Change.deletedIdOrNull(): String? =
    (this as? androidx.health.connect.client.changes.DeletionChange)?.recordId

fun Change.upsertedRecordOrNull(): Record? =
    (this as? androidx.health.connect.client.changes.UpsertionChange)?.record
