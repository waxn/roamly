package com.roamly.data.repository

import com.roamly.data.api.Comment
import com.roamly.data.api.CommentsResponse
import com.roamly.data.api.CreateCommentRequest
import com.roamly.data.api.CreateMilestoneRequest
import com.roamly.data.api.CreateTripRequest
import com.roamly.data.api.DayNoteResponse
import com.roamly.data.api.PlannedStopRequest
import com.roamly.data.api.PlannedStopResponse
import com.roamly.data.api.RoamlyApi
import com.roamly.data.api.SaveDayNoteRequest
import com.roamly.data.api.TimelineEvent
import com.roamly.data.api.TimelineResponse
import com.roamly.data.api.TripResponse
import com.roamly.data.api.TripsListResponse
import com.roamly.data.prefs.UserPreferences
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TripRepository @Inject constructor(
    private val api: RoamlyApi,
    private val prefs: UserPreferences,
) {
    suspend fun getTrips(): Result<TripsListResponse> = safeApiCall { api.getTrips() }

    suspend fun getTrip(id: Int): Result<TripResponse> = safeApiCall { api.getTrip(id) }

    suspend fun createTrip(request: CreateTripRequest): Result<Unit> {
        val deviceId = prefs.deviceId.first() ?: return Result.Error("No device registered")
        return try {
            val r = api.createTrip(
                name = request.name,
                description = request.description,
                deviceId = deviceId,
                startTime = request.startTime.ifBlank { nowIso() },
                endTime = request.endTime.ifBlank { nowIso() },
            )
            if (r.isSuccessful) Result.Success(Unit)
            else Result.Error("Failed (${r.code()})")
        } catch (e: Exception) { Result.Error(e.message ?: "Unknown error") }
    }

    suspend fun deleteTrip(id: Int) = safeApiCall { api.deleteTrip(id) }

    suspend fun togglePublic(id: Int) = safeApiCall { api.toggleTripPublic(id) }

    suspend fun getTimeline(id: Int): Result<TimelineResponse> = safeApiCall { api.getTripTimeline(id) }

    suspend fun createBlurb(tripId: Int, text: String): Result<TimelineEvent> = try {
        val r = api.createTripBlurb(tripId, text)
        if (r.isSuccessful) Result.Success(TimelineEvent(type = "blurb", id = 0, text = text))
        else Result.Error("Failed (${r.code()})")
    } catch (e: Exception) { Result.Error(e.message ?: "Unknown error") }

    suspend fun deleteBlurb(tripId: Int, blurbId: Int) = safeApiCall { api.deleteTripBlurb(tripId, blurbId) }

    suspend fun getComments(tripId: Int, blurbId: Int): Result<CommentsResponse> =
        safeApiCall { api.getTripComments(tripId, blurbId) }

    suspend fun createComment(tripId: Int, blurbId: Int, text: String): Result<Comment> = try {
        val r = api.createTripComment(tripId, blurbId, CreateCommentRequest(text))
        if (r.isSuccessful) Result.Success(Comment(id = 0, author = "", text = text, createdAt = "", canDelete = true))
        else Result.Error("Failed (${r.code()})")
    } catch (e: Exception) { Result.Error(e.message ?: "Unknown error") }

    suspend fun deleteComment(tripId: Int, commentId: Int): Result<Unit> = try {
        val r = api.deleteTripComment(tripId, commentId)
        if (r.isSuccessful) Result.Success(Unit) else Result.Error("Failed (${r.code()})")
    } catch (e: Exception) { Result.Error(e.message ?: "Unknown error") }

    suspend fun createMilestone(tripId: Int, request: CreateMilestoneRequest): Result<Unit> = try {
        val r = api.createTripMilestone(tripId, request)
        if (r.isSuccessful) Result.Success(Unit) else Result.Error("Failed (${r.code()})")
    } catch (e: Exception) { Result.Error(e.message ?: "Unknown error") }

    // --- Day Log ---
    suspend fun createDayNote(tripId: Int, date: String): Result<DayNoteResponse> =
        safeApiCall { api.createDayNote(tripId, date) }

    suspend fun saveDayNote(tripId: Int, noteId: Int, title: String, body: String, placeId: Int?): Result<DayNoteResponse> =
        safeApiCall { api.saveDayNote(tripId, noteId, SaveDayNoteRequest(title, body, placeId)) }

    suspend fun uploadDayNotePhotos(tripId: Int, noteId: Int, parts: List<okhttp3.MultipartBody.Part>): Result<DayNoteResponse> =
        safeApiCall { api.uploadDayNotePhotos(tripId, noteId, parts) }

    suspend fun deleteDayNotePhoto(tripId: Int, photoId: Int) = safeApiCall { api.deleteDayNotePhoto(tripId, photoId) }

    suspend fun deleteDayNote(tripId: Int, noteId: Int) = safeApiCall { api.deleteDayNote(tripId, noteId) }

    // --- Itinerary ---
    suspend fun createPlannedStop(tripId: Int, request: PlannedStopRequest): Result<PlannedStopResponse> =
        safeApiCall { api.createPlannedStop(tripId, request) }

    suspend fun updatePlannedStop(tripId: Int, stopId: Int, request: PlannedStopRequest): Result<PlannedStopResponse> =
        safeApiCall { api.updatePlannedStop(tripId, stopId, request) }

    suspend fun deletePlannedStop(tripId: Int, stopId: Int) = safeApiCall { api.deletePlannedStop(tripId, stopId) }

    /** Configured server origin (no trailing slash), for building absolute media URLs. */
    suspend fun serverUrl(): String = prefs.serverUrl.first()?.trimEnd('/') ?: ""

    private fun nowIso(): String = java.time.Instant.now().toString()
}
