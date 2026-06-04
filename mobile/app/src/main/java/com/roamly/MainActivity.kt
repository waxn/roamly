package com.roamly

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.roamly.data.prefs.UserPreferences
import com.roamly.ui.theme.Clay
import com.roamly.ui.RoamlyNavHost
import com.roamly.ui.theme.RoamlyTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var prefs: UserPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val prefDarkMode by prefs.darkMode.collectAsState(initial = null)
            val systemDark = isSystemInDarkTheme()
            val darkMode = prefDarkMode ?: systemDark
            RoamlyTheme(darkTheme = darkMode) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Clay.colors.backgroundBrush)
                ) {
                    RoamlyNavHost()
                }
            }
        }
    }
}
