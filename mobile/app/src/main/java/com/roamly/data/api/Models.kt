package com.roamly.data.api

import com.google.gson.annotations.SerializedName

// --- Location ---

data class LocationPoint(
    val id: Int = 0,
    val lat: Double,
    val lng: Double,
    val timestamp: String,
    val altitude: Double? = null,
    val accuracy: Double? = null,
    val speed: Double? = null,
    val battery: Double? = null,
    val city: String? = null,
    val state: String? = null,
    val country: String? = null,
    @SerializedName("country_code") val countryCode: String? = null,
    /** Which device recorded this point. The API nests locations under their
     *  device rather than repeating it per point, so this is filled in by the
     *  caller (LocationStore / MapViewModel), never by Gson. */
    val deviceId: String? = null,
)

data class DeviceLocations(
    @SerializedName("device_id") val deviceId: String,
    val name: String,
    val locations: List<LocationPoint>,
)

data class DeviceInfo(
    val id: Int = 0,
    @SerializedName("device_id") val deviceId: String,
    val name: String? = null,
)

data class DevicesResponse(val devices: List<DeviceInfo> = emptyList())

data class LocationsResponse(
    val devices: List<DeviceLocations>,
    // Cursor-pagination metadata — used by the incremental sync to page through
    // the full history in ascending order (see LocationStore.sync()).
    @SerializedName("has_more")          val hasMore: Boolean = false,
    @SerializedName("next_before_value") val nextBeforeValue: String? = null,
    @SerializedName("next_before_id")    val nextBeforeId: Int? = null,
)

// Flat, one-row-per-point shape backing the raw Location Data table (mirrors the
// web /data/ page). The same /api/locations/ payload also carries a grouped
// `devices` list for map sync — that's LocationsResponse; this reads the flat
// `locations` array instead.
data class LocationTableRow(
    val id: Int,
    val device: String = "",
    val lat: Double? = null,
    val lng: Double? = null,
    val city: String? = null,
    val state: String? = null,
    val country: String? = null,
    @SerializedName("country_code") val countryCode: String? = null,
    @SerializedName("place_name")   val placeName: String? = null,
    @SerializedName("poi_name")     val poiName: String? = null,
    @SerializedName("custom_place") val customPlace: String? = null,
    val altitude: Double? = null,
    val accuracy: Double? = null,
    val speed: Double? = null,
    val battery: Double? = null,
    val timestamp: String = "",
    val flag: String? = null,
)

data class LocationTableResponse(
    val locations: List<LocationTableRow> = emptyList(),
    @SerializedName("sort_key") val sortKey: String = "timestamp",
    @SerializedName("sort_dir") val sortDir: String = "desc",
    @SerializedName("has_more") val hasMore: Boolean = false,
    @SerializedName("next_before_value") val nextBeforeValue: String? = null,
    @SerializedName("next_before_id")    val nextBeforeId: Int? = null,
)

data class TrackPoint(
    @SerializedName("c") val coordinates: List<Double>,
    val ts: Long? = null,
    val city: String? = null,
    val state: String? = null,
    val country: String? = null,
)

data class TrackDevice(
    val id: String,
    val name: String,
    val total: Int = 0,
    val hidden: Int = 0,
    val points: List<TrackPoint> = emptyList(),
)

data class TrackResponse(
    val devices: List<TrackDevice> = emptyList(),
)

// --- Stats ---

data class StatsResponse(
    @SerializedName("total_points")   val totalPoints: Int = 0,
    val countries: Int = 0,
    val cities: Int = 0,
    val states: Int = 0,
    val devices: Int = 0,
    @SerializedName("first_location") val firstLocation: String? = null,
    @SerializedName("last_location")  val lastLocation: String? = null,
)

// --- Yearly Overview ---

data class PeriodStats(
    val points: Int = 0,
    val countries: Int = 0,
    val cities: Int = 0,
)

data class MonthlyBucket(
    val month: Int = 0,
    val points: Int = 0,
)

data class TopCity(
    val city: String,
    val country: String? = null,
    val count: Int = 0,
)

data class TopCountry(
    val country: String,
    val count: Int = 0,
)

data class YearlyOverviewResponse(
    val year: Int = 0,
    @SerializedName("this_week")  val thisWeek: PeriodStats = PeriodStats(),
    @SerializedName("last_week")  val lastWeek: PeriodStats = PeriodStats(),
    @SerializedName("this_month") val thisMonth: PeriodStats = PeriodStats(),
    @SerializedName("last_month") val lastMonth: PeriodStats = PeriodStats(),
    @SerializedName("this_year")  val thisYear: PeriodStats = PeriodStats(),
    @SerializedName("last_year")  val lastYear: PeriodStats = PeriodStats(),
    @SerializedName("monthly_breakdown") val monthlyBreakdown: List<MonthlyBucket> = emptyList(),
    @SerializedName("top_cities")    val topCities: List<TopCity> = emptyList(),
    @SerializedName("top_countries") val topCountries: List<TopCountry> = emptyList(),
)

// --- Visits ---

data class CountryVisit(
    val country: String,
    @SerializedName("country_code")   val countryCode: String? = null,
    @SerializedName("location_count") val locationCount: Int = 0,
    @SerializedName("city_count")     val cityCount: Int = 0,
)

data class CityVisit(
    val city: String,
    val state: String? = null,
    val country: String? = null,
    @SerializedName("visit_count") val visitCount: Int = 0,
)

data class VisitsResponse(
    val countries: List<CountryVisit>,
    val cities: List<CityVisit>,
)

// --- Trips ---

data class TripLatLng(
    val lat: Double,
    val lng: Double,
    val timestamp: String? = null,
    val speed: Double? = null,
    val city: String? = null,
    val country: String? = null,
)

data class TripResponse(
    val id: Int,
    val name: String,
    val description: String? = null,
    val subtitle: String? = null,
    val device: String? = null,
    @SerializedName("start_time")     val startTime: String,
    @SerializedName("end_time")       val endTime: String,
    // The list endpoint sends location_count/member_count; the detail endpoint
    // instead sends total_location_count + the full locations[] and members[].
    @SerializedName("location_count") val locationCount: Int = 0,
    @SerializedName("member_count")   val memberCount: Int = 0,
    @SerializedName("total_location_count") val totalLocationCount: Int = 0,
    val locations: List<TripLatLng> = emptyList(),
    val members: List<PalMember>? = null,
    @SerializedName("is_public")      val isPublic: Boolean = false,
    @SerializedName("is_creator")     val isCreator: Boolean = false,
    @SerializedName("public_slug")    val publicSlug: String? = null,
    // Detail-endpoint rich content (empty on the list endpoint).
    @SerializedName("cover_image")     val coverImage: String? = null,
    @SerializedName("cover_thumbnail") val coverThumbnail: String? = null,
    val body: List<BodyBlock> = emptyList(),
    val places: List<PlacePayload> = emptyList(),
    @SerializedName("planned_stops")   val plannedStops: List<PlannedStop> = emptyList(),
    @SerializedName("day_notes")       val dayNotes: List<DayNote> = emptyList(),
    val categories: List<TripCategory> = emptyList(),
    // photo id (as a string key) -> media. photo_grid blocks reference these ids.
    val photos: Map<String, MediaItem> = emptyMap(),
    @SerializedName("invite_token")    val inviteToken: String? = null,
    @SerializedName("invite_url")      val inviteUrl: String? = null,
    // username -> that member's own device track for the trip window.
    @SerializedName("member_locations") val memberLocations: Map<String, List<TripLatLng>> = emptyMap(),
) {
    /** Best available point count regardless of which endpoint produced this. */
    val pointCount: Int get() = if (totalLocationCount > 0) totalLocationCount else maxOf(locationCount, locations.size)
    /** Best available member count regardless of endpoint. */
    val memberCountResolved: Int get() = members?.size ?: memberCount
}

// A single Story block. `content` is read loosely as raw JSON and its fields are
// pulled out per block.type at render time (mirrors the web's untyped renderer);
// the document format still evolves web-side, so avoid a rigid typed union here.
data class BodyBlock(
    val type: String = "",
    val content: com.google.gson.JsonObject? = null,
)

data class PlacePayload(
    val id: Int,
    val name: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val notes: String? = null,
    val rating: Int? = null,
    val category: String? = null,
)

data class MediaItem(
    val id: Int = 0,
    val type: String = "image",   // "image" | "video"
    val url: String? = null,
    val video: String? = null,
    val thumb: String? = null,
)

data class PlannedStop(
    val id: Int,
    val name: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    @SerializedName("location_name") val locationName: String? = null,
    @SerializedName("arrival_date")  val arrivalDate: String? = null,
    val nights: Int? = null,
    val transport: String? = null,
    val notes: String? = null,
    val accommodation: String? = null,
    val order: Int = 0,
)

data class DayNotePlace(
    val id: Int,
    val name: String = "",
    val rating: Int? = null,
    val category: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

data class DayNote(
    val id: Int,
    val date: String = "",
    @SerializedName("author_id") val authorId: Int? = null,
    val author: String = "",
    val avatar: String? = null,
    val title: String = "",
    val body: String = "",
    @SerializedName("place_id") val placeId: Int? = null,
    val place: DayNotePlace? = null,
    @SerializedName("is_mine")   val isMine: Boolean = false,
    val photos: List<MediaItem> = emptyList(),
    @SerializedName("updated_at") val updatedAt: String? = null,
)

data class TripCategory(val slug: String = "", val label: String = "")

// --- Day Log write ---

data class DayNoteResponse(
    val status: String = "",
    @SerializedName("day_note") val dayNote: DayNote? = null,
)

data class SaveDayNoteRequest(
    val title: String,
    val body: String,
    @SerializedName("place_id") val placeId: Int? = null,
)

// --- Itinerary (PlannedStop) write ---
// All fields nullable so a reorder can send only `order` (Gson omits nulls, and
// the server only touches keys present in the request).

data class PlannedStopRequest(
    val name: String? = null,
    @SerializedName("location_name") val locationName: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    @SerializedName("arrival_date") val arrivalDate: String? = null,
    val nights: Int? = null,
    val transport: String? = null,
    val notes: String? = null,
    val accommodation: String? = null,
    val order: Int? = null,
)

data class PlannedStopResponse(
    val status: String = "",
    val stop: PlannedStop? = null,
)

// --- Blurb create + invites ---

data class CreateBlurbResponse(
    val status: String = "",
    @SerializedName("blurb_id") val blurbId: Int? = null,
    @SerializedName("photo_ids") val photoIds: List<Int> = emptyList(),
)

data class InviteRequest(val rotate: Boolean = false)

data class InviteResponse(
    val status: String = "",
    @SerializedName("invite_token") val inviteToken: String? = null,
    @SerializedName("invite_url") val inviteUrl: String? = null,
)

data class TripsListResponse(val trips: List<TripResponse>)

data class CreateTripRequest(
    val name: String,
    val description: String? = null,
    @SerializedName("device_id") val deviceId: String = "",
    @SerializedName("start_time") val startTime: String = "",
    @SerializedName("end_time") val endTime: String = "",
)

// --- Trip members ---

data class PalMember(
    @SerializedName("user_id")  val userId: Int,
    val username: String,
    val role: String,
    @SerializedName("joined_at") val joinedAt: String? = null,
    @SerializedName("can_remove") val canRemove: Boolean = false,
)

// --- Timeline ---

data class TimelineEvent(
    val type: String,      // "blurb" or "milestone"
    val id: Int,
    val author: String? = null,
    @SerializedName("author_id") val authorId: Int? = null,
    val text: String? = null,
    val title: String? = null,
    val description: String? = null,
    val emoji: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    @SerializedName("location_name") val locationName: String? = null,
    @SerializedName("created_at")    val createdAt: String? = null,
    val date: String? = null,
    val rating: Int? = null,
    val category: String? = null,
    val photos: List<MediaItem> = emptyList(),
    @SerializedName("can_delete")    val canDelete: Boolean = false,
    @SerializedName("comment_count") val commentCount: Int = 0,
)

data class TimelineResponse(
    val events: List<TimelineEvent>,
    val page: Int = 1,
    @SerializedName("has_more") val hasMore: Boolean = false,
)

// --- Comments ---

data class Comment(
    val id: Int,
    val author: String,
    @SerializedName("author_id") val authorId: Int? = null,
    val text: String,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("can_delete") val canDelete: Boolean = false,
)

data class CommentsResponse(val comments: List<Comment>)

data class CreateCommentResponse(
    val status: String,
    val comment: Comment? = null,
    @SerializedName("comment_id") val commentId: Int? = null,
)

// --- Request bodies (JSON) ---

data class CreateCommentRequest(val text: String)

data class AddMemberRequest(val username: String)

data class CreateMilestoneRequest(
    val title: String,
    val description: String = "",
    val emoji: String = "🏁",
    val date: String,
)

// --- Auth / API key ---

data class ApiKeyResponse(
    val status: String,
    val key: String,
    val name: String,
    val id: Int,
)

// Optional Mapbox public token, configured on the web and read here so the
// map can use the same Mapbox basemap the user picked on their other devices.
data class MapboxTokenResponse(
    @SerializedName("mapbox_token") val mapboxToken: String? = null,
)

// --- AI "Ask" ---

// Whether the requesting user has AI Ask enabled/configured on the server —
// used to decide whether to show the Ask tab at all.
data class AiConfigResponse(
    @SerializedName("ai_ask_enabled") val aiAskEnabled: Boolean = false,
    @SerializedName("configured")     val configured: Boolean = false,
)

data class AskMessage(
    val role: String,
    val content: String,
)

data class AskRequest(
    val messages: List<AskMessage>,
    @SerializedName("tz_offset") val tzOffset: Int = 0,
)

data class AskResponse(
    val reply: String? = null,
    val error: String? = null,
)

// --- In-app update ---

// Latest available Android build, reported by the server (which proxies the
// GitHub release). `downloadUrl` points at the server's own APK endpoint.
data class VersionInfo(
    @SerializedName("version_name")  val versionName: String = "",
    @SerializedName("download_url")  val downloadUrl: String = "",
    @SerializedName("release_notes") val releaseNotes: String = "",
    @SerializedName("size")          val size: Long = 0,
)

// --- Native location push ---

data class LocationPushPayload(
    @SerializedName("device_id")  val deviceId: String,
    @SerializedName("latitude")   val latitude: Double,
    @SerializedName("longitude")  val longitude: Double,
    @SerializedName("timestamp")  val timestamp: String,   // ISO-8601 UTC
    @SerializedName("altitude")   val altitude: Double?,
    @SerializedName("accuracy")   val accuracy: Float?,
    @SerializedName("speed")      val speed: Float?,
    @SerializedName("battery")    val battery: Int?,
)

data class LocationPushResponse(
    val status: String,
    @SerializedName("location_id") val locationId: Long?,
    val device: String?,
)

data class LocationBatchPushResponse(
    val status: String,
    val accepted: Int = 0,
    val submitted: Int = 0,
)

// --- Journals ---

data class JournalListItem(
    val date: String = "",
    val title: String = "",
    val snippet: String = "",
    val mood: String = "",
    val weather: String = "",
    @SerializedName("is_favorite") val isFavorite: Boolean = false,
    @SerializedName("photo_count") val photoCount: Int = 0,
    val cover: String? = null,
    @SerializedName("word_count") val wordCount: Int = 0,
    @SerializedName("location_name") val locationName: String? = null,
)

data class JournalListResponse(val entries: List<JournalListItem> = emptyList())

data class JournalStatsResponse(
    @SerializedName("current_streak") val currentStreak: Int = 0,
    @SerializedName("longest_streak") val longestStreak: Int = 0,
    @SerializedName("total_entries") val totalEntries: Int = 0,
    @SerializedName("this_year")     val thisYear: Int = 0,
    @SerializedName("this_month")    val thisMonth: Int = 0,
    @SerializedName("total_words")   val totalWords: Int = 0,
    @SerializedName("total_photos")  val totalPhotos: Int = 0,
    @SerializedName("entry_dates")   val entryDates: List<String> = emptyList(),
)

data class JournalPhoto(
    val id: Int = 0,
    val url: String? = null,
    val thumbnail: String? = null,
    val caption: String? = null,
    val order: Int = 0,
)

data class JournalEntryData(
    val exists: Boolean = false,
    val date: String = "",
    val title: String = "",
    val body: String = "",
    val mood: String = "",
    val weather: String = "",
    @SerializedName("is_favorite")   val isFavorite: Boolean = false,
    @SerializedName("location_name") val locationName: String? = null,
    /** [lng, lat] or null */
    val pin: List<Double>? = null,
    val photos: List<JournalPhoto> = emptyList(),
)

data class JournalTrack(
    /** ordered [lng, lat] pairs */
    val points: List<List<Double>> = emptyList(),
    @SerializedName("point_count") val pointCount: Int = 0,
    @SerializedName("distance_km") val distanceKm: Double = 0.0,
    val cities: List<String> = emptyList(),
    /** [lng, lat] or null */
    val centroid: List<Double>? = null,
)

data class JournalDetailResponse(
    val entry: JournalEntryData = JournalEntryData(),
    val track: JournalTrack = JournalTrack(),
)

data class JournalSaveRequest(
    val title: String? = null,
    val body: String? = null,
    val mood: String? = null,
    val weather: String? = null,
    @SerializedName("is_favorite") val isFavorite: Boolean? = null,
)

data class JournalPhotosResponse(
    val status: String = "",
    val photos: List<JournalPhoto> = emptyList(),
)

// --- Diagnostics ---

data class DiagGaps(
    @SerializedName("min_s")    val minS: Double? = null,
    @SerializedName("max_s")    val maxS: Double? = null,
    @SerializedName("avg_s")    val avgS: Double? = null,
    @SerializedName("median_s") val medianS: Double? = null,
    @SerializedName("p90_s")    val p90S: Double? = null,
    @SerializedName("over_60s")  val over60: Int = 0,
    @SerializedName("over_300s") val over300: Int = 0,
    @SerializedName("over_900s") val over900: Int = 0,
)

data class DiagAccuracy(
    val min: Double? = null,
    val max: Double? = null,
    val avg: Double? = null,
    val missing: Int = 0,
    @SerializedName("under_20")  val under20: Int = 0,
    @SerializedName("under_50")  val under50: Int = 0,
    @SerializedName("over_100")  val over100: Int = 0,
)

data class DiagSpeed(
    @SerializedName("min_ms") val minMs: Double? = null,
    @SerializedName("max_ms") val maxMs: Double? = null,
    @SerializedName("avg_ms") val avgMs: Double? = null,
    val missing: Int = 0,
)

data class DiagBattery(val min: Int? = null, val max: Int? = null)

data class DiagTimeline(
    val points: List<Int> = emptyList(),
    val coverage: List<Double> = emptyList(),
)

data class DiagSpan(
    val start: String? = null,
    val end: String? = null,
    val human: String? = null,
)

data class DiagnosticsResponse(
    val count: Int = 0,
    val error: String? = null,
    @SerializedName("points_per_hour") val pointsPerHour: Double? = null,
    @SerializedName("distance_km")     val distanceKm: Double? = null,
    val span: DiagSpan = DiagSpan(),
    val timeline: DiagTimeline = DiagTimeline(),
    val gaps: DiagGaps = DiagGaps(),
    val accuracy: DiagAccuracy = DiagAccuracy(),
    val speed: DiagSpeed = DiagSpeed(),
    val battery: DiagBattery = DiagBattery(),
)

// --- Search ---

data class SearchCity(
    val city: String = "",
    val state: String = "",
)

data class SearchDay(
    val date: String = "",
    val count: Int = 0,
    @SerializedName("first_ts") val firstTs: String? = null,
    @SerializedName("last_ts")  val lastTs: String? = null,
    @SerializedName("time_spent") val timeSpent: Int = 0,
    val cities: List<SearchCity> = emptyList(),
    val devices: List<String> = emptyList(),
)

data class SearchPlace(
    @SerializedName("place_name") val placeName: String = "",
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val days: List<SearchDay> = emptyList(),
    @SerializedName("total_points") val totalPoints: Int = 0,
)

data class SearchResponse(
    val query: String = "",
    @SerializedName("query_type")      val queryType: String = "text",
    @SerializedName("location_results") val locationResults: List<SearchDay> = emptyList(),
    @SerializedName("total_days")      val totalDays: Int = 0,
    @SerializedName("total_points")    val totalPoints: Int = 0,
    @SerializedName("place_results")   val placeResults: List<SearchPlace> = emptyList(),
    @SerializedName("places_checked")  val placesChecked: Int = 0,
    @SerializedName("needs_download")  val needsDownload: Boolean = false,
)

// --- Health (Health Connect) ---

data class HealthSampleDto(
    val kind: String = "",
    val value: Double = 0.0,
    val start: String = "",
    val end: String = "",
    @SerializedName("zone_offset_seconds") val zoneOffsetSeconds: Int? = null,
    val source: String = "",
    @SerializedName("device_id") val deviceId: String = "",
    @SerializedName("hc_id") val hcId: String = "",
    @SerializedName("last_modified") val lastModified: String? = null,
)

data class HealthSamplesPush(
    val samples: List<HealthSampleDto> = emptyList(),
    /** hc_ids Health Connect reported as deleted, so the server drops them too. */
    val deleted: List<String> = emptyList(),
)

data class HealthWorkoutDto(
    @SerializedName("hc_id") val hcId: String = "",
    val source: String = "",
    @SerializedName("device_id") val deviceId: String = "",
    val start: String = "",
    val end: String = "",
    @SerializedName("zone_offset_seconds") val zoneOffsetSeconds: Int? = null,
    @SerializedName("exercise_type") val exerciseType: Int = 0,
    @SerializedName("exercise_slug") val exerciseSlug: String = "",
    val title: String = "",
    val notes: String = "",
    @SerializedName("duration_s") val durationS: Int = 0,
    val steps: Int? = null,
    @SerializedName("distance_m") val distanceM: Double? = null,
    @SerializedName("calories_kcal") val caloriesKcal: Double? = null,
    @SerializedName("avg_heart_rate") val avgHeartRate: Double? = null,
)

data class HealthWorkoutsPush(
    val workouts: List<HealthWorkoutDto> = emptyList(),
)

data class HealthPushResponse(
    val status: String = "",
    val submitted: Int = 0,
    val accepted: Int = 0,
    val updated: Int = 0,
    val deleted: Int = 0,
)

data class ImportedWorkoutsResponse(
    @SerializedName("hc_ids") val hcIds: List<String> = emptyList(),
)

data class HealthSourceRow(
    val source: String = "",
    val kind: String = "",
    val count: Int = 0,
    val first: String? = null,
    val last: String? = null,
)

data class HealthStatusResponse(
    val connected: Boolean = false,
    @SerializedName("sample_count")  val sampleCount: Int = 0,
    @SerializedName("workout_count") val workoutCount: Int = 0,
    val earliest: String? = null,
    val latest: String? = null,
    @SerializedName("last_workout_at") val lastWorkoutAt: String? = null,
    val sources: List<HealthSourceRow> = emptyList(),
    @SerializedName("preferred_source") val preferredSource: String = "",
)
