package com.roamly.tracker.api

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

interface RoamlyApi {

    /**
     * Push a single location point.
     * Auth is injected as "Authorization: Bearer <key>" by [AuthInterceptor].
     */
    @POST("api/push/")
    suspend fun pushLocation(@Body payload: LocationPayload): Response<PushResponse>
}
