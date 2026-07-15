package com.roamly.data.repository

import com.roamly.data.api.AiConfigResponse
import com.roamly.data.api.AskMessage
import com.roamly.data.api.AskRequest
import com.roamly.data.api.AskResponse
import com.roamly.data.api.RoamlyApi
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AskRepository @Inject constructor(private val api: RoamlyApi) {

    suspend fun getConfig(): Result<AiConfigResponse> =
        safeApiCall { api.getAiConfig() }

    suspend fun sendMessage(messages: List<AskMessage>, tzOffsetMinutes: Int): Result<AskResponse> =
        safeApiCall { api.askChat(AskRequest(messages = messages, tzOffset = tzOffsetMinutes)) }
}
