package com.roamly.ui.health

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.roamly.ui.theme.Clay
import com.roamly.ui.theme.ClayButton
import com.roamly.ui.theme.ClayCard
import com.roamly.ui.theme.ClayOutlinedButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val WORKOUT_FMT = SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault())

private val WINDOWS = listOf(
    30L to "30 days",
    90L to "90 days",
    365L to "1 year",
)

/**
 * Browse the exercise sessions on this phone and import the ones worth keeping.
 *
 * Deliberately manual: nothing here is uploaded until the user taps Import, so
 * no heuristic decides on their behalf which sessions "look real".
 */
@Composable
fun HealthWorkoutsScreen(
    viewModel: HealthViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Back") }
            Text("Workouts", style = MaterialTheme.typography.titleLarge,
                 color = MaterialTheme.colorScheme.onBackground)
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            WINDOWS.forEach { (days, label) ->
                FilterChip(
                    selected = state.workoutWindowDays == days,
                    onClick = { viewModel.loadWorkouts(days) },
                    label = { Text(label) },
                )
            }
        }

        when {
            state.workoutsLoading -> Box(
                Modifier.fillMaxSize(), contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }

            state.workouts.isEmpty() -> Box(
                Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center
            ) {
                Text(
                    "No exercise sessions in this window.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(state.workouts, key = { it.hcId }) { row ->
                    // Totals are aggregated per row as it scrolls into view —
                    // doing a whole window up front is slow enough to notice.
                    LaunchedEffect(row.hcId) { viewModel.loadWorkoutTotals(row.hcId) }
                    WorkoutCard(row = row, onImport = { viewModel.importWorkout(row.hcId) })
                }
            }
        }
    }
}

@Composable
private fun WorkoutCard(row: WorkoutRow, onImport: () -> Unit) {
    ClayCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(row.label, fontWeight = FontWeight.SemiBold)
                Text(
                    WORKOUT_FMT.format(Date(row.startMs)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    buildString {
                        append(formatDuration(row.durationS))
                        row.distanceM?.let { append(" · ${"%.1f".format(it / 1000)} km") }
                        row.caloriesKcal?.let { append(" · ${it.toInt()} kcal") }
                        row.steps?.let { append(" · $it steps") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Clay.colors.moss,
                )
            }
            Spacer(Modifier.width(12.dp))
            when {
                row.importing -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                row.imported -> ClayOutlinedButton(onClick = {}, enabled = false) { Text("Imported") }
                else -> ClayButton(onClick = onImport) { Text("Import") }
            }
        }
    }
}

private fun formatDuration(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
