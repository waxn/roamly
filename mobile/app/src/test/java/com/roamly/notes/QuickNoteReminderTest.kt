package com.roamly.notes

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class QuickNoteReminderTest {

    private val zone = ZoneId.of("America/New_York")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = ZonedDateTime.of(LocalDateTime.of(y, mo, d, h, mi), zone)

    @Test fun laterTodayWhenStillAhead() {
        assertEquals(at(2026, 11, 23, 19, 30), QuickNoteReminder.nextTrigger(at(2026, 11, 23, 9, 0), 19 * 60 + 30))
    }

    @Test fun tomorrowOnceItHasPassed() {
        assertEquals(at(2026, 11, 24, 19, 30), QuickNoteReminder.nextTrigger(at(2026, 11, 23, 19, 30), 19 * 60 + 30))
        assertEquals(at(2026, 11, 24, 19, 30), QuickNoteReminder.nextTrigger(at(2026, 11, 23, 22, 0), 19 * 60 + 30))
    }

    @Test fun keepsWallClockAcrossDst() {
        // US DST ends 2026-11-01: the reminder stays at 7:30 pm local, not 6:30.
        val next = QuickNoteReminder.nextTrigger(at(2026, 10, 31, 20, 0), 19 * 60 + 30)
        assertEquals(LocalDateTime.of(2026, 11, 1, 19, 30), next.toLocalDateTime())
    }

    @Test fun clampsOutOfRangeMinutes() {
        assertEquals(at(2026, 11, 23, 23, 59), QuickNoteReminder.nextTrigger(at(2026, 11, 23, 9, 0), 99_999))
    }
}
