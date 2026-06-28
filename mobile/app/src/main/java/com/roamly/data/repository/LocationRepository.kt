package com.roamly.data.repository

import com.roamly.data.api.DiagnosticsResponse
import com.roamly.data.api.LocationsResponse
import com.roamly.data.api.RoamlyApi
import com.roamly.data.api.SearchResponse
import com.roamly.data.api.StatsResponse
import com.roamly.data.api.TrackResponse
import com.roamly.data.api.VisitsResponse
import com.roamly.data.api.YearlyOverviewResponse
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocationRepository @Inject constructor(private val api: RoamlyApi) {

    suspend fun getLocations(hours: Int? = null, startDate: String? = null, endDate: String? = null, limit: Int? = null): Result<LocationsResponse> =
        safeApiCall {
            if (hours != null)
                api.getLocations(hours = hours, limit = limit ?: 50000)
            else if (startDate != null && endDate != null)
                api.getLocations(startDate = startDate, endDate = endDate, limit = limit ?: 50000)
            else
                api.getLocations(all = 1, limit = limit ?: 50000)
        }

    suspend fun getLocationsInBbox(
        minLat: Double, maxLat: Double, minLng: Double, maxLng: Double,
        hours: Int? = null, limit: Int = 5000,
    ): Result<LocationsResponse> = safeApiCall {
        if (hours != null)
            api.getLocations(hours = hours, limit = limit, minLat = minLat, maxLat = maxLat, minLng = minLng, maxLng = maxLng)
        else
            api.getLocations(all = 1, limit = limit, minLat = minLat, maxLat = maxLat, minLng = minLng, maxLng = maxLng)
    }

    suspend fun getTrack(hours: Int? = null, startDate: String? = null, endDate: String? = null): Result<TrackResponse> =
        safeApiCall {
            if (hours != null)
                api.getTrack(hours = hours)
            else if (startDate != null && endDate != null)
                api.getTrack(startDate = startDate, endDate = endDate)
            else
                api.getTrack(all = 1)
        }

    suspend fun getStats(hours: Int? = null, startDate: String? = null, endDate: String? = null): Result<StatsResponse> =
        safeApiCall {
            if (hours != null)
                api.getStats(hours = hours)
            else if (startDate != null && endDate != null)
                api.getStats(startDate = startDate, endDate = endDate)
            else
                api.getStats(all = 1)
        }

    suspend fun getVisits(): Result<VisitsResponse> = safeApiCall { api.getVisits() }

    suspend fun search(query: String): Result<SearchResponse> = safeApiCall { api.search(q = query) }

    suspend fun getYearlyOverview(): Result<YearlyOverviewResponse> = safeApiCall { api.getYearlyOverview() }

    suspend fun getDiagnostics(deviceId: String?, hours: Int): DiagnosticsResponse {
        val resp = api.getDiagnostics(deviceId = deviceId?.ifBlank { null }, hours = hours)
        if (resp.isSuccessful) return resp.body() ?: DiagnosticsResponse()
        throw Exception("HTTP ${resp.code()}")
    }
}
