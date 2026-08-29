package com.roamly.data.repository

import com.roamly.data.api.HealthPushResponse
import com.roamly.data.api.HealthSampleDto
import com.roamly.data.api.HealthSamplesPush
import com.roamly.data.api.HealthStatusResponse
import com.roamly.data.api.HealthWorkoutDto
import com.roamly.data.api.HealthWorkoutsPush
import com.roamly.data.api.ImportedWorkoutsResponse
import com.roamly.data.api.RoamlyApi
import okhttp3.ResponseBody
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HealthRepository @Inject constructor(
    private val api: RoamlyApi,
) {
    suspend fun pushSamples(
        samples: List<HealthSampleDto>,
        deleted: List<String> = emptyList(),
    ): Result<HealthPushResponse> =
        safeApiCall { api.pushHealthSamples(HealthSamplesPush(samples, deleted)) }

    suspend fun importWorkouts(workouts: List<HealthWorkoutDto>): Result<HealthPushResponse> =
        safeApiCall { api.importHealthWorkouts(HealthWorkoutsPush(workouts)) }

    /** The hc_ids already on the server, so the browse list can mark rows imported. */
    suspend fun importedWorkoutIds(all: Boolean = true): Result<ImportedWorkoutsResponse> =
        safeApiCall { api.getImportedWorkoutIds(all = if (all) 1 else null) }

    suspend fun deleteWorkout(id: Int): Result<ResponseBody> =
        safeApiCall { api.deleteHealthWorkout(id) }

    suspend fun status(): Result<HealthStatusResponse> =
        safeApiCall { api.getHealthStatus() }

    suspend fun deleteAll(): Result<ResponseBody> =
        safeApiCall { api.deleteAllHealthData() }
}
