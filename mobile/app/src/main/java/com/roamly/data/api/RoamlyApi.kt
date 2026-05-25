package com.roamly.data.api

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface RoamlyApi {

    // --- Auth ---

    @FormUrlEncoded
    @POST("login/")
    suspend fun login(
        @Field("username") username: String,
        @Field("password") password: String,
        @Field("csrfmiddlewaretoken") csrfToken: String,
    ): Response<ResponseBody>

    @FormUrlEncoded
    @POST("api/keys/create/")
    suspend fun createApiKey(
        @Field("name") name: String = "Roamly Android",
    ): Response<ApiKeyResponse>

    // --- Push location (native tracker) ---

    @POST("api/push/")
    suspend fun pushLocation(@Body payload: LocationPushPayload): Response<LocationPushResponse>

    // --- Locations ---

    @GET("api/locations/")
    suspend fun getLocations(
        @Query("hours") hours: Int? = null,
        @Query("start_date") startDate: String? = null,
        @Query("end_date") endDate: String? = null,
        @Query("all") all: Int? = null,
        @Query("limit") limit: Int = 50000,
        @Query("min_lat") minLat: Double? = null,
        @Query("max_lat") maxLat: Double? = null,
        @Query("min_lng") minLng: Double? = null,
        @Query("max_lng") maxLng: Double? = null,
    ): Response<LocationsResponse>

    @GET("api/track/")
    suspend fun getTrack(
        @Query("hours") hours: Int? = null,
        @Query("start_date") startDate: String? = null,
        @Query("end_date") endDate: String? = null,
        @Query("all") all: Int? = null,
    ): Response<TrackResponse>

    // --- Stats ---

    @GET("api/stats/")
    suspend fun getStats(
        @Query("hours") hours: Int? = null,
        @Query("start_date") startDate: String? = null,
        @Query("end_date") endDate: String? = null,
        @Query("all") all: Int? = null,
    ): Response<StatsResponse>

    @GET("api/visits/")
    suspend fun getVisits(): Response<VisitsResponse>

    @GET("api/stats/yearly/")
    suspend fun getYearlyOverview(): Response<YearlyOverviewResponse>

    // --- Trips ---

    @GET("api/trips/")
    suspend fun getTrips(): Response<TripsListResponse>

    @GET("api/trips/{id}/")
    suspend fun getTrip(@Path("id") id: Int): Response<TripResponse>

    @FormUrlEncoded
    @POST("api/trips/create/")
    suspend fun createTrip(
        @Field("name") name: String,
        @Field("description") description: String?,
        @Field("device_id") deviceId: String,
        @Field("start_time") startTime: String,
        @Field("end_time") endTime: String,
    ): Response<ResponseBody>

    @POST("api/trips/{id}/delete/")
    suspend fun deleteTrip(@Path("id") id: Int): Response<ResponseBody>

    @POST("api/trips/{id}/toggle-public/")
    suspend fun toggleTripPublic(@Path("id") id: Int): Response<ResponseBody>

    @GET("api/trips/{id}/timeline/")
    suspend fun getTripTimeline(@Path("id") id: Int): Response<TimelineResponse>

    @FormUrlEncoded
    @POST("api/trips/{id}/blurbs/create/")
    suspend fun createTripBlurb(
        @Path("id") tripId: Int,
        @Field("text") text: String,
    ): Response<ResponseBody>

    @POST("api/trips/{tripId}/blurbs/{blurbId}/delete/")
    suspend fun deleteTripBlurb(
        @Path("tripId") tripId: Int,
        @Path("blurbId") blurbId: Int,
    ): Response<ResponseBody>

    @GET("api/trips/{tripId}/blurbs/{blurbId}/comments/")
    suspend fun getTripComments(
        @Path("tripId") tripId: Int,
        @Path("blurbId") blurbId: Int,
    ): Response<CommentsResponse>

    @POST("api/trips/{tripId}/blurbs/{blurbId}/comments/create/")
    suspend fun createTripComment(
        @Path("tripId") tripId: Int,
        @Path("blurbId") blurbId: Int,
        @Body request: CreateCommentRequest,
    ): Response<ResponseBody>

    @POST("api/trips/{tripId}/comments/{commentId}/delete/")
    suspend fun deleteTripComment(
        @Path("tripId") tripId: Int,
        @Path("commentId") commentId: Int,
    ): Response<ResponseBody>

    @POST("api/trips/{id}/milestones/create/")
    suspend fun createTripMilestone(
        @Path("id") tripId: Int,
        @Body request: CreateMilestoneRequest,
    ): Response<ResponseBody>

    @POST("api/trips/{id}/milestones/{milestoneId}/delete/")
    suspend fun deleteTripMilestone(
        @Path("id") tripId: Int,
        @Path("milestoneId") milestoneId: Int,
    ): Response<ResponseBody>

    @POST("api/trips/{id}/members/add/")
    suspend fun addTripMember(
        @Path("id") tripId: Int,
        @Body request: AddMemberRequest,
    ): Response<ResponseBody>

    @POST("api/trips/{id}/members/{userId}/remove/")
    suspend fun removeTripMember(
        @Path("id") tripId: Int,
        @Path("userId") userId: Int,
    ): Response<ResponseBody>

    // --- Pals ---

    @GET("api/pals/")
    suspend fun getPals(): Response<PalsListResponse>

    @GET("api/pals/{id}/")
    suspend fun getPal(@Path("id") id: Int): Response<PalResponse>

    @POST("api/pals/")
    suspend fun createPal(@Body request: CreatePalRequest): Response<ResponseBody>

    @POST("api/pals/{id}/update/")
    suspend fun updatePal(@Path("id") id: Int, @Body request: UpdatePalRequest): Response<ResponseBody>

    @POST("api/pals/{id}/delete/")
    suspend fun deletePal(@Path("id") id: Int): Response<ResponseBody>

    @POST("api/pals/{id}/toggle-public/")
    suspend fun togglePalPublic(@Path("id") id: Int): Response<ResponseBody>

    @GET("api/pals/{id}/timeline/")
    suspend fun getPalTimeline(@Path("id") id: Int): Response<TimelineResponse>

    @FormUrlEncoded
    @POST("api/pals/{id}/blurbs/create/")
    suspend fun createPalBlurb(
        @Path("id") palId: Int,
        @Field("text") text: String,
    ): Response<ResponseBody>

    @POST("api/pals/{palId}/blurbs/{blurbId}/delete/")
    suspend fun deletePalBlurb(
        @Path("palId") palId: Int,
        @Path("blurbId") blurbId: Int,
    ): Response<ResponseBody>

    @GET("api/pals/{palId}/blurbs/{blurbId}/comments/")
    suspend fun getPalComments(
        @Path("palId") palId: Int,
        @Path("blurbId") blurbId: Int,
    ): Response<CommentsResponse>

    @POST("api/pals/{palId}/blurbs/{blurbId}/comments/create/")
    suspend fun createPalComment(
        @Path("palId") palId: Int,
        @Path("blurbId") blurbId: Int,
        @Body request: CreateCommentRequest,
    ): Response<CreateCommentResponse>

    @POST("api/pals/{palId}/comments/{commentId}/delete/")
    suspend fun deletePalComment(
        @Path("palId") palId: Int,
        @Path("commentId") commentId: Int,
    ): Response<ResponseBody>

    @POST("api/pals/{id}/milestones/create/")
    suspend fun createPalMilestone(
        @Path("id") palId: Int,
        @Body request: CreateMilestoneRequest,
    ): Response<ResponseBody>

    @POST("api/pals/{id}/milestones/{milestoneId}/delete/")
    suspend fun deletePalMilestone(
        @Path("id") palId: Int,
        @Path("milestoneId") milestoneId: Int,
    ): Response<ResponseBody>

    @POST("api/pals/{id}/members/add/")
    suspend fun addPalMember(
        @Path("id") palId: Int,
        @Body request: AddMemberRequest,
    ): Response<ResponseBody>

    @POST("api/pals/{id}/members/{userId}/remove/")
    suspend fun removePalMember(
        @Path("id") palId: Int,
        @Path("userId") userId: Int,
    ): Response<ResponseBody>

    // --- Devices ---

    @GET("api/devices/")
    suspend fun getDevices(): Response<ResponseBody>
}
