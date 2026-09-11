package com.roamly.ui.stats

import com.roamly.data.api.CityVisit
import com.roamly.data.api.CountryVisit
import com.roamly.data.api.StatsResponse
import com.roamly.data.api.YearlyOverviewResponse
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-lived cache of the last successful stats load, so the Stats tab paints
 * the previous data instantly and refreshes in the background instead of showing
 * a skeleton and re-querying from scratch on every visit.
 *
 * Being @Singleton it outlives the Activity, so it must be wiped on sign-out
 * alongside DiskCache and LocationStore — otherwise the next account to log in
 * on this device is shown the previous one's stats until the first refresh
 * lands. See [clear], called from SettingsViewModel.logout().
 */
@Singleton
class StatsCache @Inject constructor() {
    var stats: StatsResponse? = null
    var yearly: YearlyOverviewResponse? = null
    var topCountries: List<CountryVisit> = emptyList()
    var topCities: List<CityVisit> = emptyList()
    var hasData: Boolean = false

    /** Wipe every cached figure. Called on sign-out. */
    fun clear() {
        stats = null
        yearly = null
        topCountries = emptyList()
        topCities = emptyList()
        hasData = false
    }
}
