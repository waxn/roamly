package com.roamly.ui.family

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.MailOutline
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.roamly.data.api.FamilyCircleDetailResponse
import com.roamly.ui.kit.AtlasButton
import com.roamly.ui.kit.AtlasField
import com.roamly.ui.kit.AtlasSheet
import com.roamly.ui.kit.EmptyState
import com.roamly.ui.kit.Feel
import com.roamly.ui.kit.IconAction
import com.roamly.ui.kit.ListRow
import com.roamly.ui.kit.PushedScreen
import com.roamly.ui.kit.SegmentedSwitch
import com.roamly.ui.theme.Atlas

/**
 * Family Circle, reached from the You tab in both Simple and Advanced Mode:
 * Map / Places / Circle behind one switch, plus a circle picker when the
 * account is in more than one.
 */
@Composable
fun FamilyScreen(onBack: () -> Unit, viewModel: FamilyViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val c = Atlas.colors
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showCreate by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }

    PushedScreen(
        title = state.selectedCircle?.name ?: "Family Circle",
        onBack = onBack,
        subtitle = state.selectedCircle?.let { "${it.members.size} member${if (it.members.size == 1) "" else "s"}" },
        actions = {
            if (state.circles.size > 1) IconAction(Icons.Rounded.SwapHoriz, "Switch circle", { showPicker = true })
        },
    ) {
        when {
            state.loading && state.circles.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = c.accent) }
            state.circles.isEmpty() -> EmptyState(
                Icons.Rounded.Groups, "No Family Circle yet",
                body = "Create a circle to see where your people are and get a nudge when someone arrives at or leaves a place.",
                tint = c.amber, action = "Create a circle", onAction = { showCreate = true },
            )
            state.selectedCircle?.accepted == false -> EmptyState(
                Icons.Rounded.MailOutline, "Invite pending",
                body = "Open the invite link you received to join “${state.selectedCircle?.name.orEmpty()}” — it explains what sharing your location means before you accept.",
                tint = c.textMuted,
            )
            else -> {
                SegmentedSwitch(listOf("Map", "Places", "Circle"), tab, { tab = it }, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp))
                Box(Modifier.weight(1f).padding(top = 6.dp)) {
                    AnimatedContent(tab, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "familyTab") { t ->
                        when (t) {
                            0 -> FamilyMapContent(state = state, onRefresh = viewModel::refreshLocations)
                            1 -> FamilyPlacesContent(viewModel = viewModel, state = state)
                            else -> FamilyCircleContent(viewModel = viewModel, state = state, onCreateAnother = { showCreate = true })
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        var name by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        AtlasSheet(onDismiss = { showCreate = false }, title = "New Family Circle") {
            Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                AtlasField(name, { name = it }, label = "Name", placeholder = "Our family")
                AtlasButton("Create circle", {
                    busy = true
                    viewModel.createCircle(name.trim()) { _, _ -> busy = false; showCreate = false }
                }, Modifier.fillMaxWidth(), enabled = name.isNotBlank(), loading = busy, feel = Feel.Confirm)
            }
        }
    }
    if (showPicker) {
        CirclePicker(state.circles, state.selectedCircleId, { showPicker = false }, { id -> viewModel.selectCircle(id); showPicker = false }) {
            showPicker = false; showCreate = true
        }
    }
}

@Composable
private fun CirclePicker(
    circles: List<FamilyCircleDetailResponse>,
    selected: Int?,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
    onCreateNew: () -> Unit,
) {
    val c = Atlas.colors
    AtlasSheet(onDismiss = onDismiss, title = "Switch circle") {
        circles.forEach { circle ->
            ListRow(
                circle.name,
                subtitle = if (!circle.accepted) "Invite pending" else "${circle.members.size} members",
                icon = Icons.Rounded.Groups, iconTint = c.amber,
                onClick = { onPick(circle.id) }, showChevron = false,
                trailing = if (circle.id == selected) ({ Icon(Icons.Rounded.Check, null, tint = c.accent) }) else null,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        ListRow("New circle", icon = Icons.Rounded.Add, iconTint = c.accent, onClick = onCreateNew, modifier = Modifier.padding(horizontal = 8.dp))
    }
}
