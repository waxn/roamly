package com.roamly.health

import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import com.roamly.data.api.HealthSampleDto
import com.roamly.data.api.HealthWorkoutDto
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Same UTC wire format UploadWorker uses for location timestamps. */
private val ISO: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

private fun Instant.iso(): String = ISO.format(this)

/**
 * Health Connect record -> wire DTO.
 *
 * Note StepsRecord, not StepsCadenceRecord: only the former carries a count.
 * Active and total calories are kept as separate kinds because total includes
 * BMR and active does not, so summing them together would be meaningless — the
 * web page displays active by default.
 */
fun Record.toHealthSample(deviceId: String): HealthSampleDto? {
    val meta = metadata
    val hcId = meta.id
    if (hcId.isBlank()) return null

    val (kind, value, start, end, zone) = when (this) {
        is StepsRecord -> Quint(
            "steps", count.toDouble(), startTime, endTime, startZoneOffset?.totalSeconds)
        is DistanceRecord -> Quint(
            "distance", distance.inMeters, startTime, endTime, startZoneOffset?.totalSeconds)
        is ActiveCaloriesBurnedRecord -> Quint(
            "calories_active", energy.inKilocalories, startTime, endTime,
            startZoneOffset?.totalSeconds)
        is TotalCaloriesBurnedRecord -> Quint(
            "calories_total", energy.inKilocalories, startTime, endTime,
            startZoneOffset?.totalSeconds)
        else -> return null
    }

    return HealthSampleDto(
        kind = kind,
        value = value,
        start = start.iso(),
        end = end.iso(),
        zoneOffsetSeconds = zone,
        source = meta.dataOrigin.packageName,
        deviceId = deviceId,
        hcId = hcId,
        lastModified = meta.lastModifiedTime.iso(),
    )
}

fun ExerciseSessionRecord.toHealthWorkout(
    deviceId: String,
    totals: HealthConnectManager.SessionTotals,
): HealthWorkoutDto = HealthWorkoutDto(
    hcId = metadata.id,
    source = metadata.dataOrigin.packageName,
    deviceId = deviceId,
    start = startTime.iso(),
    end = endTime.iso(),
    zoneOffsetSeconds = startZoneOffset?.totalSeconds,
    exerciseType = exerciseType,
    exerciseSlug = exerciseSlug(exerciseType),
    title = title ?: "",
    notes = notes ?: "",
    durationS = (endTime.epochSecond - startTime.epochSecond).toInt(),
    steps = totals.steps,
    distanceM = totals.distanceM,
    caloriesKcal = totals.caloriesKcal,
)

private data class Quint(
    val kind: String,
    val value: Double,
    val start: Instant,
    val end: Instant,
    val zone: Int?,
)

/**
 * Health Connect's numeric exercise type -> a stable display slug.
 *
 * Resolved on the phone rather than the server so neither the server nor the web
 * page has to carry Google's enum table; the raw integer is sent alongside, so
 * nothing is lost if the mapping later gains an entry.
 */
fun exerciseSlug(type: Int): String = when (type) {
    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> "running"
    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "running_treadmill"
    ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "walking"
    ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "hiking"
    ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> "biking"
    ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "biking_stationary"
    ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL -> "swimming"
    ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "swimming"
    ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING -> "strength_training"
    ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> "strength_training"
    ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> "yoga"
    ExerciseSessionRecord.EXERCISE_TYPE_PILATES -> "yoga"
    ExerciseSessionRecord.EXERCISE_TYPE_ROWING -> "rowing"
    ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE -> "rowing"
    ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL -> "elliptical"
    ExerciseSessionRecord.EXERCISE_TYPE_DANCING -> "dancing"
    ExerciseSessionRecord.EXERCISE_TYPE_ROCK_CLIMBING -> "climbing"
    ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT -> "workout"
    else -> "workout"
}

/** Human label for the workout browser and the imported list. */
fun exerciseLabel(type: Int): String =
    exerciseSlug(type).replace('_', ' ').replaceFirstChar { it.uppercase() }
