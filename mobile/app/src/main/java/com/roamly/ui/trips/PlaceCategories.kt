package com.roamly.ui.trips

// Mirror of ADVENTURE_PLACE_CATEGORIES (tracker/models.py) — emoji glyph + label
// per category slug. Hardcoded rather than driven off the server's `categories`
// payload so cards/markers render without waiting on the detail fetch.
val PLACE_CAT_EMOJI = mapOf(
    "restaurant" to "🍴", "cafe" to "☕", "bar" to "🍸", "hotel" to "🛏️",
    "store" to "🛍️", "attraction" to "📸", "nature" to "🌲", "transport" to "🚆",
    "town" to "🏙️", "other" to "📍",
)

val PLACE_CAT_LABEL = mapOf(
    "restaurant" to "Restaurant", "cafe" to "Café", "bar" to "Bar / Nightlife",
    "hotel" to "Hotel / Lodging", "store" to "Store / Shopping",
    "attraction" to "Attraction / Sight", "nature" to "Nature / Park",
    "transport" to "Transport", "town" to "Town / City", "other" to "Other",
)

/** "★★★☆☆" for a 1–5 rating, or empty for null/0. */
fun starString(rating: Int?): String {
    val n = (rating ?: 0).coerceIn(0, 5)
    return if (n == 0) "" else "★★★★★".substring(0, n) + "☆☆☆☆☆".substring(0, 5 - n)
}
