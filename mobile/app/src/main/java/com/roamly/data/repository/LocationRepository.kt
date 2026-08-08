package com.roamly.data.repository

import com.roamly.data.api.DiagnosticsResponse
import com.roamly.data.api.LocationTableResponse
import com.roamly.data.api.LocationsResponse
import com.roamly.data.api.RoamlyApi
import com.roamly.data.api.SearchResponse
import com.roamly.data.api.StatsResponse
import com.roamly.data.api.TrackResponse
import com.roamly.data.api.VisitsResponse
import com.roamly.data.api.YearlyOverviewResponse
import okhttp3.ResponseBody
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocationRepository @Inject constructor(private val api: RoamlyApi) {

    /** The user's optional Mapbox token (configured on the web). Returns null on
     *  any failure so the caller can just fall back to the built-in basemap. */
    suspend fun getMapboxToken(): String? = try {
        val resp = api.getMapboxToken()
        if (resp.isSuccessful) resp.body()?.mapboxToken?.trim() else null
    } catch (e: Exception) {
        null
    }

    /** device_id → display name, for labelling a point with the phone that
     *  recorded it. Returns an empty map on any failure — the caller falls back
     *  to showing the raw device id. */
    suspend fun getDeviceNames(): Map<String, String> = try {
        val resp = api.getDevices()
        if (resp.isSuccessful) {
            resp.body()?.devices.orEmpty().associate { it.deviceId to (it.name?.takeIf { n -> n.isNotBlank() } ?: it.deviceId) }
        } else emptyMap()
    } catch (e: Exception) {
        emptyMap()
    }

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

    /** One page of the raw Location Data table. `range` is either "all" or an
     *  hours count ("24"/"72"/…); the cursor (beforeValue/beforeId) is used for
     *  append pages, offset for the first page — mirroring the web /data/ page. */
    suspend fun getLocationsTable(
        range: String,
        deviceId: String? = null,
        sortKey: String = "timestamp",
        sortDir: String = "desc",
        limit: Int = 1000,
        beforeValue: String? = null,
        beforeId: Int? = null,
        offset: Int? = null,
    ): Result<LocationTableResponse> = safeApiCall {
        val all = if (range == "all") 1 else null
        val hours = if (range == "all") null else range.toIntOrNull()
        api.getLocationsTable(
            hours = hours,
            all = all,
            deviceId = deviceId?.ifBlank { null },
            limit = limit,
            offset = if (beforeValue == null) offset else null,
            sortKey = sortKey,
            sortDir = sortDir,
            beforeValue = beforeValue,
            beforeId = beforeId,
        )
    }

    suspend fun deleteLocation(id: Int): Result<ResponseBody> = safeApiCall { api.deleteLocation(id) }

    suspend fun getVisits(): Result<VisitsResponse> = safeApiCall { api.getVisits() }

    suspend fun search(query: String): Result<SearchResponse> = safeApiCall { api.search(q = query) }

    suspend fun getYearlyOverview(): Result<YearlyOverviewResponse> = safeApiCall { api.getYearlyOverview() }

    suspend fun getDiagnostics(deviceId: String?, hours: Int): DiagnosticsResponse {
        val resp = api.getDiagnostics(deviceId = deviceId?.ifBlank { null }, hours = hours)
        if (resp.isSuccessful) return resp.body() ?: DiagnosticsResponse()
        throw Exception("HTTP ${resp.code()}")
    }
}
