package com.roamly

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.roamly.data.local.LocationStore
import com.roamly.data.prefs.UserPreferences
import com.roamly.tracking.TrackingCoordinator
import com.roamly.tracking.TrackingDatabase
import com.roamly.tracking.UploadWorker
import com.roamly.ui.RoamlyNavHost
import com.roamly.ui.theme.RoamlyTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var prefs: UserPreferences
    @Inject lateinit var locationStore: LocationStore
    @Inject lateinit var db: TrackingDatabase

    /** True when the user tapped "Stop" in the tracking notification — drives the
     *  in-app confirmation dialog so the notification stop is gated too. */
    private val confirmStop = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            val prefDarkMode by prefs.darkMode.collectAsState(initial = null)
            val systemDark = isSystemInDarkTheme()
            val darkMode = prefDarkMode ?: systemDark
            RoamlyTheme(darkTheme = darkMode) {
                Box(modifier = Modifier.fillMaxSize()) {
                    RoamlyNavHost()

                    if (confirmStop.value) {
                        AlertDialog(
                            onDismissRequest = { confirmStop.value = false },
                            title = { Text("Stop tracking?") },
                            text = {
                                Text(
                                    "This turns location tracking completely off — including the " +
                                        "background keep-alive and starting on reboot. It stays off " +
                                        "until you turn it back on. Cached points still upload."
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    confirmStop.value = false
                                    lifecycleScope.launch {
                                        TrackingCoordinator.stopCompletely(applicationContext, prefs)
                                    }
                                }) { Text("Stop", color = MaterialTheme.colorScheme.error) }
                            },
                            dismissButton = {
                                TextButton(onClick = { confirmStop.value = false }) { Text("Keep tracking") }
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Every time the app comes to the foreground, pull the delta from the
        // server into the local store (throttled). Keeps on-device history fresh
        // without the screens having to call the server themselves.
        lifecycleScope.launch {
            if (!prefs.sessionId.first().isNullOrBlank()) locationStore.syncIfDue()
        }
        // Also force a *push* of any unsynced backlog. WorkManager can leave the upload
        // job asleep in exponential backoff (e.g. connectivity dropped mid-drive), which
        // a plain KEEP enqueue can't break — so opening the app would otherwise show
        // fresh local points that never reach the server. replace=true cancels the stuck
        // job and runs immediately; it's a no-op cost when the backlog is already empty.
        lifecycleScope.launch {
            if (db.pointDao().unsyncedCount() > 0) {
                UploadWorker.scheduleNow(this@MainActivity, prefs.syncOnMobileData.first(), replace = true)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_CONFIRM_STOP, false) == true) {
            confirmStop.value = true
        }
    }

    companion object {
        const val ACTION_CONFIRM_STOP = "com.roamly.CONFIRM_STOP"
        const val EXTRA_CONFIRM_STOP = "confirm_stop"
    }
}
