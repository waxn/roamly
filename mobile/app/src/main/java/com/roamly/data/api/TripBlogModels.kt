package com.roamly.data.api

import com.google.gson.annotations.SerializedName

// Trip blog — mirrors tracker/trip_blog.py's status_payload / feed_payload.

data class TripFeaturedResponse(val featured: TripFeatured? = null)

data class TripFeatured(val id: Int = 0, val name: String = "")

data class TripStatus(
    val blog: TripStatusBlog = TripStatusBlog(),
    val home: TripHome? = null,
    val people: List<TripPerson> = emptyList(),
    val together: String = "",
    val pins: List<TripPin> = emptyList(),
    val countries: List<String> = emptyList(),
    val stats: TripStats = TripStats(),
)

data class TripStatusBlog(
    val id: Int = 0,
    val name: String = "",
    val subtitle: String = "",
    @SerializedName("start_date") val startDate: String = "",
    @SerializedName("end_date") val endDate: String? = null,
    val cover: String? = null,
    val live: Boolean = true,
)

data class TripHome(val city: String = "", val country: String = "")

data class TripPerson(
    val id: Int = 0,
    val name: String = "",
    val city: String = "",
    val country: String = "",
    val seen: String = "",
    val stale: Boolean = false,
    val spot: TripSpot = TripSpot(),
)

data class TripSpot(val lat: Double = 0.0, val lon: Double = 0.0, @SerializedName("radius_m") val radiusM: Int = 500)

data class TripPin(val id: Int = 0, val lat: Double = 0.0, val lon: Double = 0.0, val date: String? = null, val city: String = "")

data class TripStats(
    val day: Int = 0,
    val countries: Int = 0,
    val cities: Int = 0,
    val posts: Int = 0,
    val upcoming: Boolean = false,
)

data class TripFeed(
    val days: List<TripDay> = emptyList(),
    @SerializedName("next_before") val nextBefore: String? = null,
)

data class TripDay(
    val date: String = "",
    val day: Int = 0,
    val city: String = "",
    val posts: List<TripPostDto> = emptyList(),
)

data class TripPostDto(
    val id: Int = 0,
    val author: TripAuthor = TripAuthor(),
    val text: String = "",
    @SerializedName("taken_at") val takenAt: String = "",
    @SerializedName("local_time") val localTime: String = "",
    val date: String? = null,
    val place: TripPlace? = null,
    val media: List<TripMedia> = emptyList(),
    val comments: List<TripCommentDto> = emptyList(),
    @SerializedName("comment_count") val commentCount: Int = 0,
    val reactions: Map<String, Int> = emptyMap(),
    @SerializedName("my_reactions") val myReactions: List<String> = emptyList(),
    val mine: Boolean = false,
)

data class TripAuthor(val id: Int = 0, val name: String = "", val avatar: String? = null)

data class TripPlace(val lat: Double = 0.0, val lon: Double = 0.0, val city: String = "", val country: String = "", val name: String = "")

data class TripMedia(
    val id: Int = 0,
    val type: String = "image",
    val status: String = "ready",
    val url: String? = null,
    val thumb: String? = null,
    val poster: String? = null,
    val w: Int? = null,
    val h: Int? = null,
)

data class TripCommentDto(
    val id: Int = 0,
    val name: String = "",
    val traveller: Boolean = false,
    val text: String = "",
    val at: String = "",
    @SerializedName("can_delete") val canDelete: Boolean = false,
)

data class TripCommentsResponse(val comments: List<TripCommentDto> = emptyList())

data class TripCommentResponse(val comment: TripCommentDto? = null)

data class TripReactResponse(val on: Boolean = false, val reactions: Map<String, Int> = emptyMap())

data class TripMyLocationResponse(val location: TripMyLocation? = null)

data class TripMyLocation(
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val city: String = "",
    val country: String = "",
    @SerializedName("age_s") val ageS: Long = 0,
)

/** Video chunk answer: `ok` / `duplicate` carry next_chunk, `complete` the row. */
data class TripChunkResponse(
    val status: String = "",
    @SerializedName("media_id") val mediaId: Int? = null,
    @SerializedName("next_chunk") val nextChunk: Int? = null,
)
