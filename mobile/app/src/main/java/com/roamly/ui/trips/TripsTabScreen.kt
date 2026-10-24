package com.roamly.ui.trips

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The Trips tab hosts both shared Adventures and the private Journal behind a
 * Trips | Journal toggle — the same shape SearchTabScreen uses for Ask/Search —
 * which is what freed a bottom-nav slot for the Data tab.
 *
 * Slots, not screens, for the same reason as DataHubScreen: RoamlyNavHost owns
 * the ViewModel scoping.
 */
@Composable
fun TripsTabScreen(
    trips: @Composable () -> Unit,
    journal: @Composable () -> Unit,
) {
    var showJournal by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(
            Modifier.fillMaxWidth().statusBarsPadding()
                .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
        ) {
            SegmentedButton(selected = !showJournal, onClick = { showJournal = false },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)) { Text("Trips") }
            SegmentedButton(selected = showJournal, onClick = { showJournal = true },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)) { Text("Journal") }
        }
        Box(Modifier.weight(1f)) { if (showJournal) journal() else trips() }
    }
}
