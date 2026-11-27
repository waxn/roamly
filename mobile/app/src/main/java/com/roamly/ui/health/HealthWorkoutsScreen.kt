package com.roamly.ui.health

import com.roamly.ui.kit.Units
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DirectionsRun
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.roamly.ui.kit.AtlasButton
import com.roamly.ui.kit.AtlasChip
import com.roamly.ui.kit.ChipRow
import com.roamly.ui.kit.EmptyState
import com.roamly.ui.kit.IconTile
import com.roamly.ui.kit.Panel
import com.roamly.ui.kit.PushedScreen
import com.roamly.ui.kit.Tag
import com.roamly.ui.kit.dockAwareBottom
import com.roamly.ui.theme.Atlas
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val WORKOUT_FMT = SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault())
private val WINDOWS = listOf(30L to "30 days", 90L to "90 days", 365L to "1 year")

/**
 * Browse the exercise sessions on this phone and import the ones worth keeping.
 * Deliberately manual: nothing is uploaded until the user taps Import.
 */
@Composable
fun HealthWorkoutsScreen(viewModel: HealthViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val c = Atlas.colors
    BackHandler(onBack = onBack)
    PushedScreen(title = "Workouts", onBack = onBack, subtitle = "On this phone") {
        ChipRow(Modifier.padding(vertical = 6.dp)) {
            WINDOWS.forEach { (days, label) -> AtlasChip(label, state.workoutWindowDays == days, { viewModel.loadWorkouts(days) }) }
        }
        when {
            state.workoutsLoading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = c.accent) }
            state.workouts.isEmpty() -> EmptyState(Icons.Rounded.DirectionsRun, "No workouts here", body = "No exercise sessions in this window.", tint = c.lime)
            else -> LazyColumn(
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 6.dp, bottom = dockAwareBottom()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.workouts, key = { it.hcId }) { row ->
                    // Totals load per row as it scrolls in — a whole window up front is slow.
                    LaunchedEffect(row.hcId) { viewModel.loadWorkoutTotals(row.hcId) }
                    Workout(row) { viewModel.importWorkout(row.hcId) }
                }
            }
        }
    }
}

@Composable
private fun Workout(row: WorkoutRow, onImport: () -> Unit) {
    val c = Atlas.colors
    Panel(Modifier.fillMaxWidth(), padding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(Icons.Rounded.DirectionsRun, tint = c.lime)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(row.label, style = MaterialTheme.typography.titleSmall, color = c.text)
                Text(WORKOUT_FMT.format(Date(row.startMs)), style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                Spacer(Modifier.height(4.dp))
                Text(
                    buildString {
                        append(formatDuration(row.durationS))
                        row.distanceM?.let { append(" · ${"%.1f".format(Units.dist(it / 1000.0))} ${Units.distUnit}") }
                        row.caloriesKcal?.let { append(" · ${it.toInt()} kcal") }
                        row.steps?.let { append(" · %,d steps".format(it)) }
                    },
                    style = MaterialTheme.typography.labelMedium, color = c.lime,
                )
            }
            Spacer(Modifier.width(10.dp))
            when {
                row.imported -> Tag("Imported", color = c.success, icon = Icons.Rounded.Check)
                else -> AtlasButton("Import", onImport, compact = true, loading = row.importing)
            }
        }
    }
}

private fun formatDuration(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
