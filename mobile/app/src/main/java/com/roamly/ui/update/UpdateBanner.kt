package com.roamly.ui.update

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.roamly.ui.kit.AtlasButton
import com.roamly.ui.kit.IconAction
import com.roamly.ui.kit.IconTile
import com.roamly.ui.kit.Panel
import com.roamly.ui.kit.PanelTone
import com.roamly.ui.kit.ButtonKind
import com.roamly.ui.theme.Atlas
import com.roamly.ui.theme.SquircleShape

/**
 * "Update available", floating just above the dock when the server reports a
 * newer build. Install downloads and launches the system installer; dismiss
 * hides it for the session.
 */
@Composable
fun UpdateBanner(
    state: UpdateUiState,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    bottomOffset: Dp = 0.dp,
) {
    val info = state.available
    AnimatedVisibility(
        visible = info != null && !state.bannerDismissed,
        modifier = modifier,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
    ) {
        Panel(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = bottomOffset + 10.dp),
            tone = PanelTone.Glass,
            padding = PaddingValues(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(Icons.Rounded.SystemUpdate, tint = Atlas.colors.cyan, size = 38.dp, iconSize = 19.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Update available", style = MaterialTheme.typography.titleSmall, color = Atlas.colors.text)
                    Text(
                        if (state.downloading) "Downloading… ${state.progress}%" else "Roamly ${info?.versionName.orEmpty()}",
                        style = MaterialTheme.typography.bodySmall, color = Atlas.colors.textMuted,
                    )
                }
                if (!state.downloading) {
                    AtlasButton("Install", onUpdate, compact = true)
                    IconAction(Icons.Rounded.Close, "Dismiss", onDismiss, container = null, tint = Atlas.colors.textMuted, size = 36.dp, iconSize = 18.dp)
                }
            }
            if (state.downloading) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { state.progress / 100f },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = Atlas.colors.accent,
                    trackColor = Atlas.colors.surfaceHighest,
                    strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
            }
            state.error?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = Atlas.colors.danger)
            }
        }
    }
}
