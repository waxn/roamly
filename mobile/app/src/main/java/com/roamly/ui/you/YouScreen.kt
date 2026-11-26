package com.roamly.ui.you

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.TableRows
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.roamly.ui.Routes
import com.roamly.ui.kit.AtlasButton
import com.roamly.ui.kit.AtlasDialog
import com.roamly.ui.kit.ButtonKind
import com.roamly.ui.kit.IconTile
import com.roamly.ui.kit.ListRow
import com.roamly.ui.kit.Panel
import com.roamly.ui.kit.RowDivider
import com.roamly.ui.kit.RowGroup
import com.roamly.ui.kit.ScreenHeader
import com.roamly.ui.kit.SectionTitle
import com.roamly.ui.kit.Tag
import com.roamly.ui.kit.dockAwareBottom
import com.roamly.ui.settings.SettingsViewModel
import com.roamly.ui.settings.TrackingHeroCard
import com.roamly.ui.theme.Atlas
import com.roamly.ui.trips.FeaturedBlogCard
import com.roamly.ui.update.UpdateUiState

/**
 * The You tab: tracking at the top (the one thing that must always be one tap
 * away), then everything that's yours — data, circle, devices — and the app.
 */
@Composable
fun YouScreen(
    viewModel: SettingsViewModel,
    username: String,
    simpleMode: Boolean,
    trackerPaired: Boolean,
    featuredBlogName: String?,
    updateState: UpdateUiState,
    onUpdate: () -> Unit,
    onOpen: (String) -> Unit,
    onLoggedOut: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val c = Atlas.colors
    val context = LocalContext.current
    var confirmSignOut by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.refreshBatteryOptimizationState() }
    val host = remember(state.serverUrl) {
        state.serverUrl.removePrefix("https://").removePrefix("http://").trimEnd('/').ifBlank { null }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(bottom = dockAwareBottom()),
    ) {
        ScreenHeader(
            title = username.ifBlank { state.username }.ifBlank { "You" },
            eyebrow = host ?: "Roamly",
        )
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            Spacer(Modifier.height(8.dp))
            TrackingHeroCard(viewModel, state)

            if (featuredBlogName != null) {
                Spacer(Modifier.height(12.dp))
                FeaturedBlogCard(featuredBlogName, { onOpen(Routes.TRIP_BLOG) })
            }

            if (!simpleMode) {
                SectionTitle("Your data", Modifier.padding(top = 22.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HubTile(Icons.Rounded.Insights, "Stats", "Distance, places, years", c.violet, Modifier.weight(1f)) { onOpen(Routes.STATS) }
                    HubTile(Icons.Rounded.Flight, "Flights", "Log, map, scan a pass", c.cyan, Modifier.weight(1f)) { onOpen(Routes.FLIGHTS) }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HubTile(Icons.Rounded.FavoriteBorder, "Health", "Steps and workouts", c.rose, Modifier.weight(1f)) { onOpen(Routes.HEALTH) }
                    HubTile(Icons.Rounded.TableRows, "Location data", "Every raw point", c.lime, Modifier.weight(1f)) { onOpen(Routes.LOCATIONS) }
                }
            }

            SectionTitle("Circle & devices", Modifier.padding(top = 22.dp))
            RowGroup {
                ListRow("Family Circle", subtitle = "See your people, get arrival alerts", icon = Icons.Rounded.Groups, iconTint = c.amber) { onOpen(Routes.FAMILY) }
                RowDivider()
                ListRow(
                    "Roamly Tracker",
                    subtitle = if (trackerPaired) "Paired — manage it here" else "Pair a hardware tracker over Bluetooth",
                    icon = Icons.Rounded.Watch, iconTint = c.sky,
                    trailing = if (trackerPaired) ({ Tag("Paired", color = c.success) }) else null,
                    onClick = { onOpen(Routes.TRACKER) },
                )
            }

            SectionTitle("App", Modifier.padding(top = 22.dp))
            RowGroup {
                updateState.available?.let { info ->
                    ListRow(
                        "Update to ${info.versionName}",
                        subtitle = if (updateState.downloading) "Downloading… ${updateState.progress}%" else "A new version of Roamly is ready",
                        icon = Icons.Rounded.SystemUpdate, iconTint = c.cyan,
                        trailing = { Tag("New", color = c.cyan, solid = true) },
                        onClick = onUpdate,
                    )
                    RowDivider()
                }
                ListRow("Settings", subtitle = "Tracking, sync, appearance, haptics", icon = Icons.Rounded.Settings, iconTint = c.textMuted) { onOpen(Routes.SETTINGS) }
                RowDivider()
                ListRow("Diagnostics", subtitle = "Coverage, gaps and the capture log", icon = Icons.Rounded.BugReport, iconTint = c.textMuted) { onOpen(Routes.DIAGNOSTICS) }
            }

            Spacer(Modifier.height(28.dp))
            AtlasButton("Sign out", { confirmSignOut = true }, Modifier.fillMaxWidth(), kind = ButtonKind.Danger, icon = Icons.Rounded.Logout)
            Spacer(Modifier.height(14.dp))
            val version = remember {
                runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "—"
            }
            Text("Roamly for Android · $version", style = MaterialTheme.typography.labelSmall, color = c.textFaint,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }

    if (confirmSignOut) {
        AtlasDialog(
            title = "Sign out?",
            onDismiss = { confirmSignOut = false },
            confirmText = "Sign out",
            onConfirm = { confirmSignOut = false; viewModel.logout(onLoggedOut) },
            destructive = true,
            icon = Icons.Rounded.Logout,
            body = { Text("Tracking stops on this phone and its cached map history is cleared. Your data on the server stays.") },
        )
    }
}

@Composable
private fun HubTile(icon: ImageVector, title: String, subtitle: String, tint: Color, modifier: Modifier, onClick: () -> Unit) {
    val c = Atlas.colors
    Panel(modifier, padding = PaddingValues(16.dp), onClick = onClick) {
        IconTile(icon, tint = tint, size = 40.dp)
        Spacer(Modifier.height(18.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = c.text)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1)
    }
}
