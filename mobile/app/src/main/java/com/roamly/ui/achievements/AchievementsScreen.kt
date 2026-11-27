package com.roamly.ui.achievements

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.UnfoldLess
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.roamly.data.api.AchBadge
import com.roamly.data.api.AchGame
import com.roamly.ui.kit.AtlasChip
import com.roamly.ui.kit.ChipRow
import com.roamly.ui.kit.EmptyState
import com.roamly.ui.kit.Foldout
import com.roamly.ui.kit.IconTile
import com.roamly.ui.kit.Panel
import com.roamly.ui.kit.PanelTone
import com.roamly.ui.kit.PushedScreen
import com.roamly.ui.kit.RefreshBox
import com.roamly.ui.kit.SectionTitle
import com.roamly.ui.kit.StatBlock
import com.roamly.ui.kit.Tag
import com.roamly.ui.kit.Units
import com.roamly.ui.kit.dockAwareBottom
import com.roamly.ui.theme.Atlas
import com.roamly.ui.theme.SquircleShape
import java.time.Duration
import java.time.OffsetDateTime
import java.util.Locale

// Same metals as the web page's tier pips.
private val TIER_COLORS = listOf(Color(0xFF8A8F98), Color(0xFFB08D57), Color(0xFFA9B2BA), Color(0xFFD4A93A), Color(0xFF8FD3DC))
private val TIER_NAMES = listOf("Locked", "Bronze", "Silver", "Gold", "Platinum")

/** Level, XP and every badge, grouped like the web's Badge Wall. Read-only: scans run on the server. */
@Composable
fun AchievementsScreen(
    onBack: () -> Unit,
    viewModel: AchievementsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val miles by Units.miles.collectAsState()
    val c = Atlas.colors
    BackHandler(onBack = onBack)

    PushedScreen(title = "Achievements", onBack = onBack, subtitle = updatedText(state.computedAt)) {
        val game = state.game
        when {
            game == null && state.pending -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = c.accent)
                    Spacer(Modifier.height(18.dp))
                    Text("Building your achievements", style = MaterialTheme.typography.titleMedium, color = c.text)
                    Text(
                        "The first scan reads your whole history${if (state.pct > 0) " · ${state.pct}%" else ""}. It runs on the server — you can leave and come back.",
                        style = MaterialTheme.typography.bodySmall, color = c.textMuted,
                        modifier = Modifier.padding(horizontal = 40.dp, vertical = 6.dp),
                    )
                }
            }
            game == null && state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = c.accent) }
            game == null -> EmptyState(
                Icons.Rounded.ErrorOutline, "Couldn't load achievements",
                body = state.error ?: "Check your connection and pull down to retry.", tint = c.rose,
                action = "Retry", onAction = viewModel::load,
            )
            else -> RefreshBox(onRefresh = viewModel::load, modifier = Modifier.fillMaxSize()) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(start = 20.dp, end = 20.dp, bottom = dockAwareBottom()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Hero(game)
                    val byId = game.badges.associateBy { it.id }
                    val almost = game.almost.mapNotNull { byId[it] }.take(3)
                    if (almost.isNotEmpty()) {
                        SectionTitle("Almost there")
                        Panel(Modifier.fillMaxWidth(), tone = PanelTone.Sunken) {
                            almost.forEachIndexed { i, b ->
                                if (i > 0) Spacer(Modifier.height(12.dp))
                                AlmostRow(b, miles)
                            }
                        }
                    }
                    BadgeGroups(game, state.filter, state.folded, miles, viewModel)
                }
            }
        }
    }
}

@Composable
private fun Hero(game: AchGame) {
    val c = Atlas.colors
    val lv = game.level
    Panel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(60.dp).clip(SquircleShape(20.dp)).background(c.accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) { Text("${lv.level}", style = MaterialTheme.typography.headlineMedium, color = c.accent) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(lv.title, style = MaterialTheme.typography.titleLarge, color = c.text)
                Text(
                    "${"%,d".format(lv.xp)} XP · ${"%,d".format((lv.nextXp - lv.xp).coerceAtLeast(0))} to level ${lv.level + 1}",
                    style = MaterialTheme.typography.bodySmall, color = c.textMuted,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Bar(lv.progress.toFloat(), c.accent)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatBlock("${game.unlocked}/${game.total}", "badges", small = true)
            StatBlock("${game.tiersEarned}/${game.maxTiers}", "tiers", small = true)
        }
    }
}

@Composable
private fun Bar(fraction: Float, tint: Color, modifier: Modifier = Modifier) {
    val c = Atlas.colors
    Box(modifier.fillMaxWidth().height(6.dp).clip(SquircleShape(50)).background(c.surfaceHigh)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(tint))
    }
}

@Composable
private fun AlmostRow(b: AchBadge, miles: Boolean) {
    val c = Atlas.colors
    val tint = TIER_COLORS[(b.tier + 1).coerceIn(1, 4)]
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconTile(achievementIcon(b.icon), tint = tint, size = 36.dp, iconSize = 18.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(b.name, style = MaterialTheme.typography.titleSmall, color = c.text, modifier = Modifier.weight(1f), maxLines = 1)
                Text("${(b.progress * 100).toInt()}% → ${TIER_NAMES[(b.tier + 1).coerceIn(1, 4)].lowercase()}",
                    style = MaterialTheme.typography.labelSmall, color = c.textMuted)
            }
            Spacer(Modifier.height(6.dp))
            Bar(b.progress.toFloat(), tint)
        }
    }
}

@Composable
private fun BadgeGroups(
    game: AchGame,
    filter: String,
    folded: Set<String>,
    miles: Boolean,
    vm: AchievementsViewModel,
) {
    val c = Atlas.colors
    // Only categories with something to show are offered, like the web filter row.
    val groups = game.groups.filter { g -> game.badges.any { it.group == g.id && it.available } }
    val shown = groups.filter { filter == "all" || it.id == filter }

    SectionTitle("Badges", Modifier.padding(top = 6.dp))
    ChipRow(contentPadding = PaddingValues(0.dp)) {
        AtlasChip("All", filter == "all", { vm.setFilter("all") })
        groups.forEach { g ->
            AtlasChip(g.name, filter == g.id, { vm.setFilter(g.id) }, icon = achievementGroupIcon(g.id))
        }
    }
    val allFolded = shown.isNotEmpty() && shown.all { it.id in folded }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        AtlasChip(
            if (allFolded) "Expand all" else "Collapse all", false,
            { vm.setAllFolded(shown.map { it.id }, !allFolded) },
            icon = if (allFolded) Icons.Rounded.UnfoldMore else Icons.Rounded.UnfoldLess,
            tint = c.textMuted,
        )
    }
    shown.forEach { g ->
        val badges = game.badges.filter { it.group == g.id && it.available }
        Foldout(
            title = g.name,
            open = g.id !in folded,
            onToggle = { vm.toggleFold(g.id) },
            icon = achievementGroupIcon(g.id),
            badge = "${badges.count { it.tier > 0 }}/${badges.size}",
        ) {
            badges.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { b -> BadgeTile(b, miles, Modifier.weight(1f)) }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun BadgeTile(b: AchBadge, miles: Boolean, modifier: Modifier) {
    val c = Atlas.colors
    val tint = TIER_COLORS[b.tier.coerceIn(0, 4)]
    Panel(modifier, tone = PanelTone.Sunken, padding = PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(achievementIcon(b.icon), tint = tint, size = 38.dp, iconSize = 20.dp, filled = b.tier > 0)
            Spacer(Modifier.weight(1f))
            Tag(TIER_NAMES[b.tier.coerceIn(0, 4)], color = tint)
        }
        Spacer(Modifier.height(10.dp))
        Text(b.name, style = MaterialTheme.typography.titleSmall, color = c.text, maxLines = 1)
        Text(b.blurb, style = MaterialTheme.typography.labelSmall, color = c.textMuted, maxLines = 2, modifier = Modifier.height(30.dp))
        Spacer(Modifier.height(6.dp))
        Text(valueText(b, miles), style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace), color = c.text, maxLines = 1)
        if (b.next != null) {
            Spacer(Modifier.height(8.dp))
            Bar(b.progress.toFloat(), TIER_COLORS[(b.tier + 1).coerceIn(1, 4)])
        }
    }
}

/** "1,240 km · next 5,000 km" with the unit following the app's km / mi choice. */
private fun valueText(b: AchBadge, miles: Boolean): String {
    val v = b.value ?: return "no data yet"
    fun fmt(x: Double): String = when (b.unit) {
        "km" -> if (miles) "%,.0f mi".format(x * 0.621371) else "%,.0f km".format(x)
        "km/h" -> if (miles) "%,.0f mph".format(x * 0.621371) else "%,.0f km/h".format(x)
        "m" -> if (miles) "%,.0f ft".format(x * 3.28084) else "%,.0f m".format(x)
        "%" -> "%,.1f%%".format(x)
        else -> "%,.0f ${b.unit}".format(Locale.getDefault(), x).trim()
    }
    return if (b.next == null) "${fmt(v)} · maxed" else "${fmt(v)} · next ${fmt(b.next)}"
}

private fun updatedText(iso: String?): String? {
    if (iso == null) return null
    val mins = runCatching { Duration.between(OffsetDateTime.parse(iso), OffsetDateTime.now()).toMinutes() }.getOrNull() ?: return null
    return when {
        mins < 1 -> "Updated just now"
        mins < 60 -> "Updated $mins min ago"
        mins < 1440 -> "Updated ${mins / 60} h ago"
        else -> "Updated ${mins / 1440} d ago"
    }
}
