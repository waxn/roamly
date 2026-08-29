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
import com.roamly.ui.theme.ClayButton
import com.roamly.ui.theme.ClayCard
import com.roamly.ui.theme.ClaySectionHeader
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
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            ClayCard {
                ClaySectionHeader(title = "Health data in Roamly", icon = Icons.Rounded.MonitorHeart)
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
                ClayButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Got it") }
            }
        }
    }
}
