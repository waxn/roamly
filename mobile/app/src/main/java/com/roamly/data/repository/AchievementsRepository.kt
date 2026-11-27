package com.roamly.data.repository

import com.roamly.data.api.AchievementsResponse
import com.roamly.data.api.RoamlyApi
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AchievementsRepository @Inject constructor(
    private val api: RoamlyApi,
) {
    suspend fun load(): Result<AchievementsResponse> = safeApiCall { api.getAchievements() }
}
