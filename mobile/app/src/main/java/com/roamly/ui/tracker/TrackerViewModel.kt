package com.roamly.ui.tracker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roamly.data.api.NotificationItem
import com.roamly.data.api.RoamlyApi
import com.roamly.data.prefs.UserPreferences
import com.roamly.tracker.TrackerLink
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The Tracker tab. Live state comes straight from [TrackerLink]'s flows; the
 * "which one was with you?" prompts come from the server's notifications,
 * filtered to the ones involving the tracker.
 */
@HiltViewModel
class TrackerViewModel @Inject constructor(
    val link: TrackerLink,
    private val api: RoamlyApi,
    prefs: UserPreferences,
) : ViewModel() {

    val trackerName: StateFlow<String?> = prefs.trackerBleName
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _prompts = MutableStateFlow<List<NotificationItem>>(emptyList())
    val prompts: StateFlow<List<NotificationItem>> = _prompts.asStateFlow()
    private val _promptError = MutableStateFlow<String?>(null)
    val promptError: StateFlow<String?> = _promptError.asStateFlow()

    fun startSession() = link.startSession()
    fun endSession() = link.endSession()

    fun refreshPrompts() {
        viewModelScope.launch {
            try {
                val r = api.getNotifications()
                val body = r.body()
                if (!r.isSuccessful || body == null) { _promptError.value = "Couldn't load prompts"; return@launch }
                val trackers = body.trackerDevices.orEmpty().toSet()
                // Apart prompts always involve the tracker; duplicate-track ones
                // only matter here when the tracker is one of the pair.
                _prompts.value = body.items.filter { item ->
                    item.overlap == "apart" || item.devices.any { it.id in trackers }
                }
                _promptError.value = null
            } catch (e: Exception) {
                _promptError.value = "Couldn't reach the server"
            }
        }
    }

    /** Hide [deviceId]'s points for the prompt's window, then reload. */
    fun hide(item: NotificationItem, deviceId: Int) {
        viewModelScope.launch {
            try { api.hideOverlap(item.id, mapOf("device_id" to deviceId)) } catch (_: Exception) {}
            refreshPrompts()
        }
    }

    fun keepBoth(item: NotificationItem) {
        viewModelScope.launch {
            try { api.dismissOverlap(item.id, emptyMap()) } catch (_: Exception) {}
            refreshPrompts()
        }
    }
}
