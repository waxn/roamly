package com.roamly.notes

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "Open the note sheet" handed from the reminder notification to the UI.
 * MainActivity [request]s it from the tapped intent; RoamlyNavHost brings the
 * Map tab forward and MapScreen opens the sheet, then [consume]s it. Same
 * pattern as IncomingShare.
 */
object QuickNoteLaunch {
    private val _requested = MutableStateFlow(false)
    val requested: StateFlow<Boolean> = _requested.asStateFlow()

    fun request() { _requested.value = true }
    fun consume() { _requested.value = false }
}
