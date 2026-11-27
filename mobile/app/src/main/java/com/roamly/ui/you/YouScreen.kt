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

            NotificationsOffNotice()

            if (featuredBlogName != null) {
                Spacer(Modifier.height(12.dp))
                FeaturedBlogCard(featuredBlogName, { onOpen(Routes.TRIP_BLOG) })
            }

            // Everything lives in one grid of panels: one tap to anywhere.
            val tiles = buildList<Hub> {
                if (!simpleMode) {
                    add(Hub(Icons.Rounded.Insights, "Stats", "Distance, places, years", c.violet) { onOpen(Routes.STATS) })
                    add(Hub(Icons.Rounded.Flight, "Flights", "Log, map, scan a pass", c.cyan) { onOpen(Routes.FLIGHTS) })
                    add(Hub(Icons.Rounded.FavoriteBorder, "Health", "Steps, sleep, workouts", c.rose) { onOpen(Routes.HEALTH) })
                    add(Hub(Icons.Rounded.TableRows, "Raw data", "Points, trash, cells and more", c.lime) { onOpen(Routes.LOCATIONS) })
                }
                add(Hub(Icons.Rounded.Groups, "Family Circle", "Your people and alerts", c.amber) { onOpen(Routes.FAMILY) })
                add(Hub(Icons.Rounded.Watch, "Tracker", if (trackerPaired) "Paired" else "Pair over Bluetooth", c.sky) { onOpen(Routes.TRACKER) })
                updateState.available?.let { info ->
                    add(Hub(Icons.Rounded.SystemUpdate, "Update", if (updateState.downloading) "Downloading… ${updateState.progress}%" else "Version ${info.versionName}", c.cyan, onUpdate))
                }
                add(Hub(Icons.Rounded.Settings, "Settings", "Tracking, sync, look", c.textMuted) { onOpen(Routes.SETTINGS) })
                add(Hub(Icons.Rounded.BugReport, "Diagnostics", "Coverage and capture log", c.textMuted) { onOpen(Routes.DIAGNOSTICS) })
            }
            Spacer(Modifier.height(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                tiles.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { h -> HubTile(h.icon, h.title, h.subtitle, h.tint, Modifier.weight(1f), h.onClick) }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
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

/** Shown only while notifications are blocked: reminders and Family alerts can't reach you then. */
@Composable
private fun NotificationsOffNotice() {
    val context = LocalContext.current
    val c = Atlas.colors
    var enabled by remember { mutableStateOf(androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                enabled = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    if (enabled) return
    Spacer(Modifier.height(12.dp))
    Panel(Modifier.fillMaxWidth(), tone = com.roamly.ui.kit.PanelTone.Accent, padding = PaddingValues(16.dp), onClick = {
        runCatching {
            context.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }) {
        Text("Notifications are off", style = MaterialTheme.typography.titleSmall, color = c.text)
        Text("Your evening reminder and Family Circle alerts can't reach you. Tap to turn them on.",
            style = MaterialTheme.typography.bodySmall, color = c.textMuted)
    }
}

private data class Hub(val icon: ImageVector, val title: String, val subtitle: String, val tint: Color, val onClick: () -> Unit)

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
