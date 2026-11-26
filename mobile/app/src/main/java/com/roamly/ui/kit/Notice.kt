package com.roamly.ui.kit

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.roamly.ui.theme.Atlas
import kotlinx.coroutines.delay

/**
 * A transient message that floats in above the bottom edge (and the dock),
 * stays a moment and leaves. Hand it a [message]; [onShown] clears it upstream.
 */
@Composable
fun Notice(message: String?, onShown: () -> Unit, modifier: Modifier = Modifier, isError: Boolean = false) {
    val haptics = rememberHaptics()
    var shown by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(message) {
        if (message != null) {
            shown = message
            if (isError) haptics.reject() else haptics.confirm()
            delay(2800)
            shown = null
            delay(250)
            onShown()
        }
    }
    AnimatedVisibility(
        visible = shown != null,
        modifier = modifier,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
    ) {
        Panel(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = dockAwareBottom(12.dp)),
            tone = PanelTone.Glass,
            padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 13.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(if (isError) Atlas.colors.danger else Atlas.colors.success)
                Spacer(Modifier.width(10.dp))
                Text(shown.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = Atlas.colors.text)
            }
        }
    }
}
