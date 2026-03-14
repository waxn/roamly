package com.roamly.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onLoggedOut: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Connection
            SectionHeader("Connection")
            LabeledValue("Server", state.serverUrl.ifBlank { "Not set" })
            LabeledValue("Username", state.username.ifBlank { "Not set" })
            LabeledValue("Device ID", state.deviceId.take(8).ifBlank { "Not set" })

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            // Tracking
            SectionHeader("Location Tracking")
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Track Location", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (state.trackingEnabled) "Currently tracking" else "Not tracking",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.trackingEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = state.trackingEnabled,
                    onCheckedChange = viewModel::setTrackingEnabled
                )
            }

            Spacer(Modifier.height(12.dp))

            Text(
                "Update interval: ${state.trackingIntervalSeconds}s",
                style = MaterialTheme.typography.bodyMedium
            )
            Slider(
                value = state.trackingIntervalSeconds.toFloat(),
                onValueChange = { viewModel.setTrackingInterval(it.roundToInt()) },
                valueRange = 5f..300f,
                steps = 58, // ~5s steps
                modifier = Modifier.fillMaxWidth()
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                Text("5s", style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.weight(1f))
                Text("5min", style = MaterialTheme.typography.labelSmall)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            // About
            SectionHeader("About")
            LabeledValue("App", "Roamly for Android")
            LabeledValue("Version", "1.0.0")

            Spacer(Modifier.height(24.dp))

            // Logout
            Button(
                onClick = { viewModel.logout(onLoggedOut) },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Disconnect")
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
