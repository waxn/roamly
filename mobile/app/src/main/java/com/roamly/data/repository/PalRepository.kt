package com.roamly.data.repository

import com.roamly.data.api.AddMemberRequest
import com.roamly.data.api.Comment
import com.roamly.data.api.CommentsResponse
import com.roamly.data.api.CreateCommentRequest
import com.roamly.data.api.CreateMilestoneRequest
import com.roamly.data.api.CreatePalRequest
import com.roamly.data.api.PalMember
import com.roamly.data.api.PalResponse
import com.roamly.data.api.PalsListResponse
import com.roamly.data.api.RoamlyApi
import com.roamly.data.api.TimelineEvent
import com.roamly.data.api.TimelineResponse
import com.roamly.data.api.UpdatePalRequest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PalRepository @Inject constructor(private val api: RoamlyApi) {

    suspend fun getPals(): Result<PalsListResponse> = safeApiCall { api.getPals() }

    suspend fun getPal(id: Int): Result<PalResponse> = safeApiCall { api.getPal(id) }

    suspend fun createPal(request: CreatePalRequest): Result<Unit> = try {
        val r = api.createPal(request)
        if (r.isSuccessful) Result.Success(Unit) else Result.Error("Failed (${r.code()})")
    } catch (e: Exception) { Result.Error(e.message ?: "Unknown error") }

    suspend fun updatePal(id: Int, name: String, description: String): Result<Unit> = try {
        val r = api.updatePal(id, UpdatePalRequest(name, description))
        if (r.isSuccessful) Result.Success(Unit) else Result.Error("Failed (${r.code()})")
    } catch (e: Exception) { Result.Error(e.message ?: "Unknown error") }

    suspend fun deletePal(id: Int): Result<Unit> = try {
        val r = api.deletePal(id)
        if (r.isSuccessful) Result.Success(Unit) else Result.Error("Failed (${r.code()})")
    } catch (e: Exception) { Result.Error(e.message ?: "Unknown error") }

    suspend fun togglePublic(id: Int): Result<Unit> = try {
        val r = api.togglePalPublic(id)
        if (r.isSuccessful) Result.Success(Unit) else Result.Error("Failed (${r.code()})")
    } catch (e: Exception) { Result.Error(e.message ?: "Unknown error") }

    suspend fun getTimeline(id: Int): Result<TimelineResponse> = safeApiCall { api.getPalTimeline(id) }

    suspend fun createBlurb(palId: Int, text: String): Result<TimelineEvent> = try {
        val r = api.createPalBlurb(palId, text)
        if (r.isSuccessful) Result.Success(TimelineEvent(type = "blurb", id = 0, text = text))
        else Result.Error("Failed (${r.code()})")
    } catch (e: Exception) { Result.Error(e.message ?: "Unknown error") }

    suspend fun deleteBlurb(palId: Int, blurbId: Int) = safeApiCall { api.deletePalBlurb(palId, blurbId) }

    suspend fun getComments(palId: Int, blurbId: Int): Result<CommentsResponse> =
        safeApiCall { api.getPalComments(palId, blurbId) }

    suspend fun createComment(palId: Int, blurbId: Int, text: String): Result<Comment> = try {
        val r = api.createPalComment(palId, blurbId, CreateCommentRequest(text))
        if (r.isSuccessful) {
            val body = r.body()
            val comment = body?.comment ?: Comment(
                id = body?.commentId ?: 0,
                author = "",
                text = text,
                createdAt = "",
                canDelete = true,
            )
            Result.Success(comment)
        } else Result.Error("Failed (${r.code()})")
    } catch (e: Exception) { Result.Error(e.message ?: "Unknown error") }

    suspend fun deleteComment(palId: Int, commentId: Int): Result<Unit> = try {
        val r = api.deletePalComment(palId, commentId)
        if (r.isSuccessful) Result.Success(Unit) else Result.Error("Failed (${r.code()})")
    } catch (e: Exception) { Result.Error(e.message ?: "Unknown error") }

    suspend fun createMilestone(palId: Int, request: CreateMilestoneRequest): Result<Unit> = try {
        val r = api.createPalMilestone(palId, request)
        if (r.isSuccessful) Result.Success(Unit) else Result.Error("Failed (${r.code()})")
    } catch (e: Exception) { Result.Error(e.message ?: "Unknown error") }

    suspend fun addMember(palId: Int, username: String): Result<PalMember> = try {
        val r = api.addPalMember(palId, AddMemberRequest(username))
        if (r.isSuccessful) Result.Success(PalMember(userId = 0, username = username, role = "member"))
        else Result.Error("Failed (${r.code()})")
    } catch (e: Exception) { Result.Error(e.message ?: "Unknown error") }

    suspend fun removeMember(palId: Int, userId: Int): Result<Unit> = try {
        val r = api.removePalMember(palId, userId)
        if (r.isSuccessful) Result.Success(Unit) else Result.Error("Failed (${r.code()})")
    } catch (e: Exception) { Result.Error(e.message ?: "Unknown error") }
}
