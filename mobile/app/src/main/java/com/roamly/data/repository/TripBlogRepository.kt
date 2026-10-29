package com.roamly.data.repository

import com.google.gson.JsonParser
import com.roamly.data.api.RoamlyApi
import com.roamly.data.api.TripCommentDto
import com.roamly.data.api.TripFeatured
import com.roamly.data.api.TripFeed
import com.roamly.data.api.TripMyLocation
import com.roamly.data.api.TripReactResponse
import com.roamly.data.api.TripStatus
import com.roamly.data.prefs.UserPreferences
import javax.inject.Inject
import javax.inject.Singleton

/** Trip blog reads and the small writes (comment, react, delete). Posting goes
 *  through [com.roamly.tripblog.TripPostOutbox] + TripPostWorker instead, so a
 *  post survives bad hotel wifi and a killed app. */
@Singleton
class TripBlogRepository @Inject constructor(
    private val api: RoamlyApi,
    private val prefs: UserPreferences,
) {
    /** Ask the server which trip is featured and cache it (drives the Trip tab). */
    suspend fun refreshFeatured(): TripFeatured? {
        val r = runCatching { api.getFeaturedTrip() }.getOrNull() ?: return null
        if (!r.isSuccessful) return null
        val f = r.body()?.featured
        prefs.setFeaturedTrip(f?.id, f?.name)
        return f
    }

    suspend fun status(id: Int): Result<TripStatus> = call { api.getTripStatus(id) }

    suspend fun feed(id: Int, before: String?): Result<TripFeed> = call { api.getTripFeed(id, before) }

    suspend fun comments(id: Int, postId: Int): Result<List<TripCommentDto>> =
        when (val r = call { api.getTripBlogComments(id, postId) }) {
            is Result.Success -> Result.Success(r.data.comments)
            is Result.Error -> r
        }

    suspend fun comment(id: Int, postId: Int, text: String): Result<TripCommentDto> =
        when (val r = call { api.postTripComment(id, mapOf("post" to postId, "text" to text)) }) {
            is Result.Success -> r.data.comment?.let { Result.Success(it) } ?: Result.Error("No comment returned")
            is Result.Error -> r
        }

    suspend fun deleteComment(id: Int, commentId: Int) = call { api.deleteTripComment(id, commentId) }

    suspend fun react(id: Int, postId: Int, emoji: String): Result<TripReactResponse> =
        call { api.reactTripPost(id, mapOf("post" to postId, "emoji" to emoji)) }

    suspend fun deletePost(id: Int, postId: Int) = call { api.deleteTripPost(id, postId) }

    suspend fun myLocation(id: Int): TripMyLocation? =
        (call { api.getTripMyLocation(id) } as? Result.Success)?.data?.location

    /** Like safeApiCall, but surfaces the server's own {"error": …} message. */
    private suspend fun <T> call(block: suspend () -> retrofit2.Response<T>): Result<T> = try {
        val r = block()
        val body = r.body()
        if (r.isSuccessful && body != null) Result.Success(body)
        else Result.Error(errorMessage(r.errorBody()?.string()) ?: "Server error ${r.code()}")
    } catch (e: Exception) {
        Result.Error(e.message ?: "Connection problem")
    }

    private fun errorMessage(raw: String?): String? = runCatching {
        JsonParser.parseString(raw ?: return null).asJsonObject.get("error")?.asString
    }.getOrNull()
}
