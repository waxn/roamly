package com.roamly.ui.search

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.roamly.ui.ask.AskScreen
import com.roamly.ui.ask.AskViewModel

/**
 * The Search tab: history search, plus the AI Ask chat when the user has a
 * provider configured. A segmented toggle switches between the two; with no AI
 * configured the toggle is hidden and the tab is search-only — search must stay
 * reachable regardless, since it no longer lives on the map.
 */
@Composable
fun SearchTabScreen(
    askViewModel: AskViewModel,
    onOpenMapDate: (String) -> Unit,
    onFocusMap: (lat: Double, lng: Double) -> Unit,
) {
    val askState by askViewModel.state.collectAsState()
    var showAsk by rememberSaveable { mutableStateOf(false) }

    // If AI Ask is turned off on the web while sitting on the Ask half, fall back
    // to search rather than showing a pane that can't be reached any more.
    LaunchedEffect(askState.askEnabled) {
        if (!askState.askEnabled) showAsk = false
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        if (askState.askEnabled) {
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
            ) {
                SegmentedButton(
                    selected = showAsk,
                    onClick = { showAsk = true },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    icon = {},
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text("Ask", modifier = Modifier.padding(start = 6.dp))
                        }
                    },
                )
                SegmentedButton(
                    selected = !showAsk,
                    onClick = { showAsk = false },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    icon = {},
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text("Search", modifier = Modifier.padding(start = 6.dp))
                        }
                    },
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    "Search",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
        }

        if (showAsk) {
            AskScreen(viewModel = askViewModel, onOpenMapDate = onOpenMapDate, showHeader = false)
        } else {
            SearchScreen(
                onPlaceClick = { lat, lng -> onFocusMap(lat, lng) },
                onDayClick = { day -> onOpenMapDate(day.date) },
            )
        }
    }
}
