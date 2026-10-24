package com.roamly.ui.data

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The Data tab: everything Roamly has recorded about you, one chip each —
 * the app's counterpart of the website's Data menu. Pages that used to hide
 * behind buttons in Settings (Health, Location data) live here now.
 *
 * Each page is passed in as a slot rather than built here, so RoamlyNavHost
 * keeps deciding which ViewModels are Activity-scoped (see activityOwner
 * there) and this file stays layout only.
 */
enum class DataPage(val label: String) { STATS("Stats"), FLIGHTS("Flights"), HEALTH("Health"), LOCATIONS("Location data") }

@Composable
fun DataHubScreen(
    initial: DataPage = DataPage.STATS,
    page: @Composable (DataPage) -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf(initial) }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DataPage.entries.forEach { p ->
                FilterChip(selected = selected == p, onClick = { selected = p }, label = { Text(p.label) })
            }
        }
        Box(Modifier.weight(1f)) { page(selected) }
    }
}
