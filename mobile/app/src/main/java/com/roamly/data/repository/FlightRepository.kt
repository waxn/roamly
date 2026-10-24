package com.roamly.data.repository

import com.google.gson.JsonParser
import com.roamly.data.api.FlightDto
import com.roamly.data.api.FlightLookupResponse
import com.roamly.data.api.FlightsResponse
import com.roamly.data.api.RoamlyApi
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of saving a flight: the 409 duplicate case is a real answer, not an error. */
sealed class SaveFlightResult {
    data class Saved(val flight: FlightDto) : SaveFlightResult()
    data class AlreadyLogged(val existingId: Int) : SaveFlightResult()
    data class Failed(val message: String) : SaveFlightResult()
}

@Singleton
class FlightRepository @Inject constructor(
    private val api: RoamlyApi,
) {
    suspend fun list(): Result<FlightsResponse> = safeApiCall { api.getFlights() }

    suspend fun delete(id: Int) = safeApiCall { api.deleteFlight(id) }

    suspend fun tracks() = safeApiCall { api.getFlightTracks() }
    suspend fun matchTrack(id: Int) = safeApiCall { api.matchFlightTrack(id) }
    suspend fun unlinkTrack(id: Int) = safeApiCall { api.unlinkFlightTrack(id) }
    suspend fun matchAllTracks() = safeApiCall { api.matchAllFlightTracks() }

    /** AeroDataBox legs, or the server's own explanation when it refused. */
    suspend fun lookup(number: String, date: String): Result<FlightLookupResponse> = try {
        val r = api.lookupFlight(number, date)
        if (r.isSuccessful) Result.Success(r.body() ?: FlightLookupResponse())
        else Result.Error(errorMessage(r.errorBody()?.string()) ?: "Lookup failed (${r.code()}).")
    } catch (e: Exception) {
        Result.Error(e.message ?: "Lookup failed.")
    }

    /** Create ([id] null) or update a flight. `fields` follows the server's names. */
    suspend fun save(id: Int?, fields: Map<String, Any?>): SaveFlightResult = try {
        val body = fields.filterValues { it != null }.mapValues { it.value!! }
        val r = if (id == null) api.createFlight(body) else api.updateFlight(id, body)
        val flight = r.body()?.flight
        when {
            r.isSuccessful && flight != null -> SaveFlightResult.Saved(flight)
            r.code() == 409 -> {
                val err = r.errorBody()?.string()
                val existing = runCatching {
                    JsonParser.parseString(err).asJsonObject.get("existing_id").asInt
                }.getOrNull()
                if (existing != null) SaveFlightResult.AlreadyLogged(existing)
                else SaveFlightResult.Failed(errorMessage(err) ?: "Already logged.")
            }
            else -> SaveFlightResult.Failed(errorMessage(r.errorBody()?.string()) ?: "Could not save (${r.code()}).")
        }
    } catch (e: Exception) {
        SaveFlightResult.Failed(e.message ?: "Could not save.")
    }

    suspend fun uploadPass(id: Int, jpeg: ByteArray): Result<FlightDto?> = try {
        val part = MultipartBody.Part.createFormData(
            "image", "boarding_pass.jpg", jpeg.toRequestBody("image/jpeg".toMediaTypeOrNull()))
        val r = api.uploadBoardingPass(id, part)
        if (r.isSuccessful) Result.Success(r.body()?.flight)
        else Result.Error(errorMessage(r.errorBody()?.string()) ?: "Photo upload failed (${r.code()}).")
    } catch (e: Exception) {
        Result.Error(e.message ?: "Photo upload failed.")
    }

    private fun errorMessage(body: String?): String? = runCatching {
        JsonParser.parseString(body).asJsonObject.get("error").asString
    }.getOrNull()
}
