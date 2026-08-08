package com.roamly.data.api

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

interface RoamlyApi {

    // --- Auth ---

    @FormUrlEncoded
    @POST("login/")
    suspend fun login(
        @Field("username") username: String,
        @Field("password") password: String,
        @Field("csrfmiddlewaretoken") csrfToken: String,
    ): Response<ResponseBody>

    /** Idempotent: returns the account's single durable tracking key, minting one
     *  only if none exists. Used to set up tracking from the app without ever
     *  creating duplicate keys. */
    @POST("api/keys/app/")
    suspend fun ensureAppApiKey(): Response<ApiKeyResponse>

    /** The user's optional Mapbox token (set on the web). Read-only here — the
     *  app uses it to render Mapbox basemaps but never changes it. */
    @GET("api/profile/mapbox-token/")
    suspend fun getMapboxToken(): Response<MapboxTokenResponse>

    // --- AI "Ask" ---

    /** Whether the user has AI Ask enabled/configured — gates the Ask tab. */
    @GET("api/profile/ai-config/")
    suspend fun getAiConfig(): Response<AiConfigResponse>

    /** One Ask turn: full message history in, assistant reply out. */
    @POST("api/ask/")
    suspend fun askChat(@Body payload: AskRequest): Response<AskResponse>

    // --- In-app update ---

    /** Latest available Android build (server proxies the GitHub release). */
    @GET("api/mobile/version/")
    suspend fun getLatestVersion(): Response<VersionInfo>

    /** Download the latest signed APK. `@Streaming` so the body isn't buffered
     *  fully in memory. */
    @Streaming
    @GET("api/mobile/apk/")
    suspend fun downloadApk(): Response<ResponseBody>

    // --- Push location (native tracker) ---

    @POST("api/push/")
    suspend fun pushLocation(@Body payload: LocationPushPayload): Response<LocationPushResponse>

    /** Batch-upload up to 500 points in one request. Falls back to [pushLocation]
     *  if the server returns 404/405 (older instance without the endpoint). */
    @POST("api/push/batch/")
    suspend fun pushLocationBatch(@Body payload: List<LocationPushPayload>): Response<LocationBatchPushResponse>

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
        @Query("sort_dir") sortDir: String? = null,
        @Query("before_value") beforeValue: String? = null,
        @Query("before_id") beforeId: Int? = null,
    ): Response<LocationsResponse>

    /** Same /api/locations/ endpoint as [getLocations], read as the flat
     *  one-row-per-point `locations` array for the raw Location Data table.
     *  Sort/filter/cursor params mirror the web /data/ page exactly. */
    @GET("api/locations/")
    suspend fun getLocationsTable(
        @Query("hours") hours: Int? = null,
        @Query("all") all: Int? = null,
        @Query("device_id") deviceId: String? = null,
        @Query("limit") limit: Int = 1000,
        @Query("offset") offset: Int? = null,
        @Query("sort_key") sortKey: String? = null,
        @Query("sort_dir") sortDir: String? = null,
        @Query("before_value") beforeValue: String? = null,
        @Query("before_id") beforeId: Int? = null,
    ): Response<LocationTableResponse>

    /** Delete a single GPS point (ownership-scoped server-side). HTTP DELETE +
     *  csrf-exempt, so the session cookie alone authorises it. */
    @DELETE("api/locations/{id}/delete/")
    suspend fun deleteLocation(@Path("id") id: Int): Response<ResponseBody>

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

    @GET("api/search/")
    suspend fun search(
        @Query("q") q: String,
        @Query("mode") mode: String = "text",
    ): Response<SearchResponse>

    @GET("api/stats/yearly/")
    suspend fun getYearlyOverview(): Response<YearlyOverviewResponse>

    @GET("api/diagnostics/location/")
    suspend fun getDiagnostics(
        @Query("device_id") deviceId: String? = null,
        @Query("hours") hours: Int? = null,
        @Query("all") all: Int? = null,
    ): Response<DiagnosticsResponse>

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

    // --- Journals ---

    @GET("api/journals/")
    suspend fun getJournals(
        @Query("year") year: Int? = null,
        @Query("month") month: Int? = null,
    ): Response<JournalListResponse>

    @GET("api/journals/stats/")
    suspend fun getJournalStats(): Response<JournalStatsResponse>

    @GET("api/journals/{date}/")
    suspend fun getJournalDetail(@Path("date") date: String): Response<JournalDetailResponse>

    @POST("api/journals/{date}/save/")
    suspend fun saveJournal(
        @Path("date") date: String,
        @Body body: JournalSaveRequest,
    ): Response<ResponseBody>

    @POST("api/journals/{date}/delete/")
    suspend fun deleteJournal(@Path("date") date: String): Response<ResponseBody>

    @Multipart
    @POST("api/journals/{date}/photos/")
    suspend fun uploadJournalPhotos(
        @Path("date") date: String,
        @Part photos: List<okhttp3.MultipartBody.Part>,
    ): Response<JournalPhotosResponse>

    @POST("api/journals/photos/{id}/delete/")
    suspend fun deleteJournalPhoto(@Path("id") id: Int): Response<ResponseBody>

    // --- Devices ---

    @GET("api/devices/")
    suspend fun getDevices(): Response<DevicesResponse>
}
