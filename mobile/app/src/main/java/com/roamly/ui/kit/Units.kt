package com.roamly.ui.kit

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * Kilometres or miles, app-wide. The choice saved on the web (Settings → speed unit,
 * mirrored to the server as `distance_unit`) wins; until one is saved the phone's region
 * decides. The last answer is kept on the device so a cold start and offline use agree.
 * Everything the server sends is metric — convert only at the point of display.
 */
object Units {
    private const val PREFS = "roamly_units"
    private const val KEY = "distance_unit"
    private const val KM_TO_MI = 0.621371

    private val _miles = MutableStateFlow(localeUsesMiles())
    val miles: StateFlow<Boolean> = _miles

    private fun localeUsesMiles() = Locale.getDefault().country in setOf("US", "GB", "LR", "MM")

    /** Load the last known choice; call once at start-up. */
    fun init(context: Context) {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        _miles.value = resolve(saved)
    }

    /** Apply what the server holds ("mph", "kmh", or blank/unknown for "not chosen"). */
    fun apply(context: Context, serverUnit: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, serverUnit).apply()
        _miles.value = resolve(serverUnit)
    }

    private fun resolve(unit: String?) = when (unit) {
        "mph" -> true
        "kmh" -> false
        else -> localeUsesMiles()
    }

    val isMiles: Boolean get() = _miles.value
    val distUnit: String get() = if (isMiles) "mi" else "km"
    val speedUnit: String get() = if (isMiles) "mph" else "km/h"

    /** Kilometres in, the display unit out. */
    fun dist(km: Double): Double = if (isMiles) km * KM_TO_MI else km
    /** Km/h in, the display unit out. */
    fun speed(kmh: Double): Double = dist(kmh)
}
