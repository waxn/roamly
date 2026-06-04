package com.roamly.ui.stats

import com.roamly.data.api.CityVisit
import com.roamly.data.api.CountryVisit
import com.roamly.data.api.StatsResponse
import com.roamly.data.api.YearlyOverviewResponse
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-lived cache of the last successful stats load. The StatsViewModel is
 * recreated every time you navigate back to the Stats tab; this singleton lets it
 * paint the previous data instantly and refresh in the background instead of
 * showing a skeleton and re-querying the DB from scratch each visit.
 */
@Singleton
class StatsCache @Inject constructor() {
    var stats: StatsResponse? = null
    var yearly: YearlyOverviewResponse? = null
    var topCountries: List<CountryVisit> = emptyList()
    var topCities: List<CityVisit> = emptyList()
    var hasData: Boolean = false
}
