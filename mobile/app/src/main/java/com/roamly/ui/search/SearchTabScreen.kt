package com.roamly.ui.search

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.roamly.ui.ask.AskScreen
import com.roamly.ui.ask.AskViewModel
import com.roamly.ui.kit.ScreenHeader
import com.roamly.ui.kit.SegmentedSwitch
import com.roamly.ui.theme.Atlas

/**
 * The Ask tab: the AI chat over your history and plain search, behind one
 * switch. Without an AI provider configured on the server the switch is gone
 * and the tab is search only (and a config change to off while on Ask falls
 * back to search).
 */
@Composable
fun SearchTabScreen(
    askViewModel: AskViewModel,
    onOpenMapDate: (String) -> Unit,
    onFocusMap: (lat: Double, lng: Double) -> Unit,
) {
    val askState by askViewModel.state.collectAsState()
    // 0 = Ask, 1 = Search
    var page by rememberSaveable { mutableIntStateOf(0) }
    val onAsk = askState.askEnabled && page == 0

    Column(Modifier.fillMaxSize().background(Atlas.colors.bg)) {
        ScreenHeader(title = if (onAsk) "Ask" else "Search", eyebrow = "Your history")
        if (askState.askEnabled) {
            SegmentedSwitch(
                options = listOf("Ask", "Search"),
                selected = page,
                onSelect = { page = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
            )
        }
        Box(Modifier.weight(1f)) {
            AnimatedContent(onAsk, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "askPage") { ask ->
                if (ask) {
                    AskScreen(viewModel = askViewModel, onOpenMapDate = onOpenMapDate)
                } else {
                    SearchScreen(
                        onPlaceClick = { lat, lng -> onFocusMap(lat, lng) },
                        onDayClick = { day -> onOpenMapDate(day.date) },
                    )
                }
            }
        }
    }
}
