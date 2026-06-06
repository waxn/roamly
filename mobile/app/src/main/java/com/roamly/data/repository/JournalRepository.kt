package com.roamly.data.repository

import com.roamly.data.api.JournalDetailResponse
import com.roamly.data.api.JournalListResponse
import com.roamly.data.api.JournalPhotosResponse
import com.roamly.data.api.JournalSaveRequest
import com.roamly.data.api.JournalStatsResponse
import com.roamly.data.api.RoamlyApi
import okhttp3.MultipartBody
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class JournalRepository @Inject constructor(private val api: RoamlyApi) {

    suspend fun list(year: Int? = null, month: Int? = null): Result<JournalListResponse> =
        safeApiCall { api.getJournals(year, month) }

    suspend fun stats(): Result<JournalStatsResponse> =
        safeApiCall { api.getJournalStats() }

    suspend fun detail(date: String): Result<JournalDetailResponse> =
        safeApiCall { api.getJournalDetail(date) }

    suspend fun save(date: String, req: JournalSaveRequest): Result<Unit> =
        when (val r = safeApiCall { api.saveJournal(date, req) }) {
            is Result.Success -> Result.Success(Unit)
            is Result.Error -> r
        }

    suspend fun delete(date: String): Result<Unit> =
        when (val r = safeApiCall { api.deleteJournal(date) }) {
            is Result.Success -> Result.Success(Unit)
            is Result.Error -> r
        }

    suspend fun uploadPhotos(date: String, parts: List<MultipartBody.Part>): Result<JournalPhotosResponse> =
        safeApiCall { api.uploadJournalPhotos(date, parts) }

    suspend fun deletePhoto(id: Int): Result<Unit> =
        when (val r = safeApiCall { api.deleteJournalPhoto(id) }) {
            is Result.Success -> Result.Success(Unit)
            is Result.Error -> r
        }
}
