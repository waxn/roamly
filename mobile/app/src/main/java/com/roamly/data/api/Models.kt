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
)

data class DeviceLocations(
    @SerializedName("device_id") val deviceId: String,
    val name: String,
    val locations: List<LocationPoint>,
)

data class LocationsResponse(
    val devices: List<DeviceLocations>,
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
) {
    /** Best available point count regardless of which endpoint produced this. */
    val pointCount: Int get() = if (totalLocationCount > 0) totalLocationCount else maxOf(locationCount, locations.size)
    /** Best available member count regardless of endpoint. */
    val memberCountResolved: Int get() = members?.size ?: memberCount
}

data class TripsListResponse(val trips: List<TripResponse>)

data class CreateTripRequest(
    val name: String,
    val description: String? = null,
    @SerializedName("device_id") val deviceId: String = "",
    @SerializedName("start_time") val startTime: String = "",
    @SerializedName("end_time") val endTime: String = "",
)

// --- Pals ---

data class PalMember(
    @SerializedName("user_id")  val userId: Int,
    val username: String,
    val role: String,
    @SerializedName("joined_at") val joinedAt: String? = null,
    @SerializedName("can_remove") val canRemove: Boolean = false,
)

data class PalResponse(
    val id: Int,
    val name: String,
    val description: String? = null,
    @SerializedName("start_date")   val startDate: String,
    @SerializedName("end_date")     val endDate: String,
    val creator: String? = null,
    @SerializedName("is_public")    val isPublic: Boolean = false,
    @SerializedName("member_count") val memberCount: Int = 0,
    val role: String? = null,
    val members: List<PalMember>? = null,
)

data class PalsListResponse(val pals: List<PalResponse>)

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

data class CreatePalRequest(
    val name: String,
    val description: String = "",
    @SerializedName("start_date") val startDate: String,
    @SerializedName("end_date")   val endDate: String,
)

data class UpdatePalRequest(
    val name: String,
    val description: String = "",
)

// --- Auth / API key ---

data class ApiKeyResponse(
    val status: String,
    val key: String,
    val name: String,
    val id: Int,
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
