package com.roamly.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.roamly.ui.kit.AtlasButton
import com.roamly.ui.kit.IconTile
import com.roamly.ui.kit.Panel
import com.roamly.ui.theme.Atlas
import com.roamly.ui.theme.RoamlyTheme

/**
 * The "why does this app want my health data" screen.
 *
 * Health Connect refuses to show its grant sheet unless the app declares a
 * rationale entry point, and this is what the user sees when they tap through
 * from Health Connect's own permission page — so it is a real screen with real
 * copy, not a stub.
 */
class HealthPermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { RoamlyTheme { RationaleScreen(onClose = { finish() }) } }
    }
}

@Composable
private fun RationaleScreen(onClose: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Panel(Modifier.fillMaxWidth()) {
                IconTile(Icons.Rounded.MonitorHeart, tint = Atlas.colors.rose, size = 48.dp, iconSize = 24.dp)
                Spacer(Modifier.height(14.dp))
                Text("Health data in Roamly", style = MaterialTheme.typography.titleLarge, color = Atlas.colors.text)
                Spacer(Modifier.height(12.dp))
                Text(
                    "Roamly reads your steps, distance and calories so you can see them " +
                        "alongside your travel history on your own Roamly server.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Workouts stay under your control: Roamly shows you the exercise " +
                        "sessions on your phone and only uploads the ones you choose to import.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Roamly never writes anything back to Health Connect, and your data only " +
                        "ever leaves this phone to reach the Roamly server you signed in to.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(16.dp))
                AtlasButton("Got it", onClose, Modifier.fillMaxWidth())
            }
        }
    }
}
