package com.roamly.ui.trips

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.JsonObject
import com.roamly.data.api.BodyBlock
import com.roamly.data.api.MediaItem
import com.roamly.data.api.PlacePayload

// ── JsonObject field helpers (block content is read loosely) ────────────────
private fun JsonObject?.str(key: String): String? =
    this?.get(key)?.takeIf { !it.isJsonNull }?.asString

private fun JsonObject?.int(key: String, default: Int): Int =
    try { this?.get(key)?.takeIf { !it.isJsonNull }?.asInt ?: default } catch (e: Exception) { default }

private fun JsonObject?.intList(key: String): List<Int> =
    try {
        this?.getAsJsonArray(key)?.mapNotNull { it.takeIf { e -> !e.isJsonNull }?.asInt } ?: emptyList()
    } catch (e: Exception) { emptyList() }

// Callout accent colors, keyed like the web's data-color values.
private val CALLOUT_COLORS = mapOf(
    "mint" to Color(0xFF0DBFAC), "coral" to Color(0xFFFF6B6B), "amber" to Color(0xFFF7B731),
    "lavender" to Color(0xFF9B8EF7), "sky" to Color(0xFF5BA4E5),
)

/**
 * Renders one Story block. Unknown/future block types render nothing so a
 * document authored on web can't crash the viewer. `onMapEmbedTap` opens the
 * full trip map in lieu of a live embedded map.
 */
@Composable
fun StoryBlock(
    block: BodyBlock,
    places: List<PlacePayload>,
    photos: Map<String, MediaItem>,
    serverUrl: String,
    onMapEmbedTap: () -> Unit,
) {
    val c = block.content
    when (block.type) {
        "heading" -> {
            val level = c.int("level", 2)
            val style = when (level) {
                1 -> MaterialTheme.typography.headlineMedium
                2 -> MaterialTheme.typography.headlineSmall
                else -> MaterialTheme.typography.titleLarge
            }
            Text(
                c.str("text").orEmpty(),
                style = style,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }

        "paragraph" -> {
            Text(
                inlineText(c.str("text").orEmpty(), places),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        "divider" -> HorizontalDivider(Modifier.padding(vertical = 4.dp))

        "callout" -> {
            val accent = CALLOUT_COLORS[c.str("color") ?: "mint"] ?: CALLOUT_COLORS["mint"]!!
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(accent.copy(alpha = 0.1f))
                    .border(1.dp, accent.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                    .padding(14.dp),
            ) {
                Text(c.str("emoji") ?: "📍", fontSize = 20.sp)
                Spacer(Modifier.width(10.dp))
                Text(
                    c.str("text").orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }

        "map_embed" -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .clickable { onMapEmbedTap() },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.Map, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(6.dp))
                    Text("View map", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        "photo_grid" -> {
            val ids = c.intList("photo_ids")
            val media = ids.mapNotNull { photos[it.toString()] }
            if (media.isNotEmpty()) {
                MediaThumbnailRow(media = media, serverUrl = serverUrl, modifier = Modifier.fillMaxWidth())
            }
        }

        "location_card" -> {
            val placeId = c.int("place_id", -1)
            val place = places.firstOrNull { it.id == placeId }
            if (place != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val emoji = place.category?.let { PLACE_CAT_EMOJI[it] }
                    if (emoji != null) Text(emoji, fontSize = 22.sp)
                    else Icon(Icons.Rounded.Place, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(place.name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                        PlaceMeta(place.rating, place.category)
                        place.notes?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        "timeline" -> {
            val events = try {
                c?.getAsJsonArray("events")?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }
            } catch (e: Exception) { null } ?: emptyList()
            if (events.isNotEmpty()) {
                Column(Modifier.fillMaxWidth()) {
                    c.str("title")?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                        Spacer(Modifier.height(8.dp))
                    }
                    events.forEach { ev ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Box(
                                modifier = Modifier.padding(top = 6.dp, end = 10.dp).size(8.dp)
                                    .clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.primary),
                            )
                            Column {
                                ev.str("date")?.takeIf { it.isNotBlank() }?.let {
                                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                                ev.str("title")?.takeIf { it.isNotBlank() }?.let {
                                    Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
                                }
                                ev.str("note")?.takeIf { it.isNotBlank() }?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }

        else -> { /* unknown/unsupported block — render nothing */ }
    }
}

/** A category badge + star rating row, shared by location cards and blurb cards. */
@Composable
fun PlaceMeta(rating: Int?, category: String?) {
    val hasCat = !category.isNullOrBlank()
    val stars = starString(rating)
    if (!hasCat && stars.isEmpty()) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (hasCat) {
            Text(
                "${PLACE_CAT_EMOJI[category] ?: ""} ${PLACE_CAT_LABEL[category] ?: category}".trim(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (stars.isNotEmpty()) {
            Text(stars, style = MaterialTheme.typography.labelMedium, color = Color(0xFFF7B731))
        }
    }
}

/** Replace inline [^pin:ID] refs with a sequential number styled in the accent
 *  color, mirroring the web's numbered pin badges. */
private fun inlineText(text: String, places: List<PlacePayload>): androidx.compose.ui.text.AnnotatedString {
    val re = Regex("""\[\^pin:(\d+)\]""")
    return buildAnnotatedString {
        var last = 0
        var counter = 0
        re.findAll(text).forEach { m ->
            append(text.substring(last, m.range.first))
            counter++
            withStyle(SpanStyle(color = Color(0xFFE8763D), fontWeight = FontWeight.Bold)) {
                append(" [$counter] ")
            }
            last = m.range.last + 1
        }
        append(text.substring(last))
    }
}
