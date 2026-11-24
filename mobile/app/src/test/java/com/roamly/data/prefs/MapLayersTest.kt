package com.roamly.data.prefs

import com.roamly.data.api.LocationPoint
import com.roamly.ui.map.MapUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapLayersTest {

    @Test fun defaultsMatchTheWeb() {
        val d = MapLayers()
        assertTrue(d.filledGaps && d.hideSuspect)
        assertFalse(d.fog || d.hideTrack || d.places || d.modeFilterActive)
        assertEquals(2, d.trackOn)
        assertEquals(0, d.displayOn)
    }

    @Test fun encodeRoundTrips() {
        val l = MapLayers(fog = true, filledGaps = false, hideTrack = true, places = true,
            modes = setOf("walk", "none"), openTrack = true, openDisplay = false)
        assertEquals(l, MapLayers.decode(l.encode()))
    }

    @Test fun decodeIsTolerant() {
        assertEquals(MapLayers(), MapLayers.decode(null))
        assertEquals(MapLayers(), MapLayers.decode("junk;;=;nope=1"))
        assertEquals(MapLayers(fog = true), MapLayers.decode("fog=1"))
        assertEquals(setOf("walk"), MapLayers.decode("modes=walk,teleport").modes)
        assertEquals(emptySet<String>(), MapLayers.decode("modes=").modes)
    }

    @Test fun blankModeCountsAsUnclassified() {
        val l = MapLayers(modes = setOf("none"))
        assertTrue(l.showsMode(null) && l.showsMode("") && l.showsMode(" "))
        assertFalse(l.showsMode("walk"))
    }

    private fun pt(id: Int, flag: String? = null, mode: String? = null) =
        LocationPoint(id = id, lat = 0.0, lng = 0.0, timestamp = "", flag = flag, transportMode = mode)

    @Test fun visibleLocationsAppliesTheTrackFilters() {
        val pts = listOf(pt(1), pt(2, flag = "suspect"), pt(3, mode = "walk"), pt(4, mode = "vehicle"))
        val ids = { l: MapLayers -> MapUiState(locations = pts, layers = l).visibleLocations().map { it.id } }
        assertEquals(listOf(1, 3, 4), ids(MapLayers()))
        assertEquals(listOf(1, 2, 3, 4), ids(MapLayers(hideSuspect = false)))
        assertEquals(listOf(3), ids(MapLayers(modes = setOf("walk"))))
        assertEquals(emptyList<Int>(), ids(MapLayers(hideTrack = true)))
    }
}
