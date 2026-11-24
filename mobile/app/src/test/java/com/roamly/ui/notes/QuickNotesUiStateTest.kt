package com.roamly.ui.notes

import com.roamly.data.api.QuickNote
import com.roamly.ui.map.DateRange
import com.roamly.ui.map.MapUiState
import com.roamly.ui.map.TimePeriod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class QuickNotesUiStateTest {

    private val today = LocalDate.of(2026, 11, 23)
    private fun d(day: Int) = LocalDate.of(2026, 11, day)
    private fun state(start: LocalDate?, end: LocalDate?, day: LocalDate? = null, notes: List<QuickNote> = emptyList()) =
        QuickNotesUiState(today = today, range = start?.let { it to end!! }, day = day, notes = notes)

    @Test fun hiddenForAllTimeAndLongerThanAMonth() {
        assertTrue(state(null, null).hidden)
        assertTrue(state(today.minusDays(31), today).hidden)          // 32 days
        assertFalse(state(today.minusDays(30), today).hidden)         // 31 days
        assertFalse(state(today.minusDays(7), today).hidden)
    }

    @Test fun oneDayRangeShowsThatDaysNote() {
        val s = state(d(20), d(20), day = d(20), notes = listOf(QuickNote("2026-11-20", "Long walk")))
        assertEquals(d(20), s.focus)
        assertEquals("Long walk", s.dayNote)
    }

    @Test fun todayUsesTheFreshTodayNote() {
        val s = state(d(22), today, day = today).copy(todayNote = "Gym")
        assertEquals("Gym", s.dayNote)
    }

    @Test fun multiDayFocusesTodayOrTheLastDay() {
        assertEquals(today, state(d(17), today).focus)
        assertEquals(d(16), state(d(10), d(16)).focus)
        assertEquals("", state(d(10), d(16)).dayNote)
    }

    @Test fun noteDayFollowsThePeriod() {
        assertEquals(today, MapUiState(timePeriod = TimePeriod.H24).noteDay(today))
        assertEquals(today, MapUiState(timePeriod = TimePeriod.H6).noteDay(today))
        assertNull(MapUiState(timePeriod = TimePeriod.D7).noteDay(today))
        assertEquals(d(20), MapUiState(timePeriod = TimePeriod.CUSTOM, customDateRange = DateRange(d(20), d(20))).noteDay(today))
        assertNull(MapUiState(timePeriod = TimePeriod.CUSTOM, customDateRange = DateRange(d(14), d(20))).noteDay(today))
    }
}
