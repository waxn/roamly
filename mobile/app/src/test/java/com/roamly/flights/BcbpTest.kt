package com.roamly.flights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class BcbpTest {

    private val today = LocalDate.of(2026, 10, 23)

    /** One leg's 35 mandatory characters, padded to the spec's widths. */
    private fun leg(
        pnr: String, from: String, to: String, carrier: String, flight: String,
        doy: String, cabin: String, seat: String, seq: String, status: String = "1",
    ) = pnr.padEnd(7) + from + to + carrier.padEnd(3) + flight.padEnd(5) + doy + cabin +
        seat + seq.padEnd(5) + status

    private fun hex2(n: Int) = n.toString(16).uppercase().padStart(2, '0')

    /** A repeated-conditional block: ticket + frequent flyer. */
    private fun repeated(ticketAirline: String, serial: String, ffAirline: String, ff: String): String {
        val body = ticketAirline + serial + "0" + "0" + "AC " + ffAirline.padEnd(3) + ff.padEnd(16) + " " + "2PC" + "N"
        return hex2(body.length) + body
    }

    @Test
    fun parsesTheSpecSampleMandatoryOnly() {
        val raw = "M1DESMARAIS/LUC       EABC123 YULFRAAC 0834 326J001A0025 100"
        val p = Bcbp.parse(raw, today)
        assertNotNull(p)
        p!!
        assertEquals("DESMARAIS/LUC", p.nameRaw)
        assertEquals("Luc Desmarais", p.passengerName)
        assertEquals(1, p.legs.size)
        val l = p.legs[0]
        assertEquals("ABC123", l.bookingRef)
        assertEquals("YUL", l.from)
        assertEquals("FRA", l.to)
        assertEquals("AC834", l.flightNumber)
        assertEquals(326, l.dayOfYear)
        assertEquals(LocalDate.of(2026, 11, 22), l.date)
        assertEquals("J", l.cabin)
        assertEquals("business", l.seatClass)
        assertEquals("1A", l.seat)
        assertEquals("25", l.checkinSeq)
        assertEquals("", l.ticketNumber)
    }

    @Test
    fun parsesTwoLegsWithConditionalData() {
        val leg1 = leg("ABC123", "YUL", "FRA", "AC", "0834", "326", "J", "003A", "0027")
        val rep1 = repeated("014", "1234567890", "AC", "1234567890123")
        val var1 = ">6" + "05" + "1WW63" + rep1
        val leg2 = leg("DEF456", "FRA", "GVA", "LH", "3664", "327", "C", "012C", "0002")
        val rep2 = repeated("014", "1234567891", "LH", "992003")
        val raw = "M2" + "DESMARAIS/LUC MR".padEnd(20) + "E" +
            leg1 + hex2(var1.length) + var1 +
            leg2 + hex2(rep2.length) + rep2
        val p = Bcbp.parse(raw, today)!!
        assertEquals("Luc Desmarais", p.passengerName)
        assertEquals(2, p.legs.size)
        assertEquals("AC834", p.legs[0].flightNumber)
        assertEquals("0141234567890", p.legs[0].ticketNumber)
        assertEquals("AC 1234567890123", p.legs[0].frequentFlyer)
        assertEquals("FRA", p.legs[1].from)
        assertEquals("GVA", p.legs[1].to)
        assertEquals("LH3664", p.legs[1].flightNumber)
        assertEquals("12C", p.legs[1].seat)
        assertEquals("0141234567891", p.legs[1].ticketNumber)
        assertEquals("LH 992003", p.legs[1].frequentFlyer)
        assertEquals(LocalDate.of(2026, 11, 23), p.legs[1].date)
    }

    @Test
    fun keepsAFlightNumberSuffixLetter() {
        val raw = "M1" + "DOE/JANE".padEnd(20) + "E" +
            leg("XYZ789", "JFK", "LAX", "B6", "0023A", "100", "Y", "023F", "0101") + "00"
        val l = Bcbp.parse(raw, today)!!.legs[0]
        assertEquals("B623A", l.flightNumber)
        assertEquals("economy", l.seatClass)
        assertEquals("23F", l.seat)
    }

    @Test
    fun resolvesTheYearNearestToday() {
        // Late in the year, early-January day numbers are the coming flight.
        assertEquals(LocalDate.of(2027, 1, 3), Bcbp.resolveDate(3, LocalDate.of(2026, 12, 30)))
        // Just after New Year, day 365 is the flight a week ago.
        assertEquals(LocalDate.of(2026, 12, 31), Bcbp.resolveDate(365, LocalDate.of(2027, 1, 5)))
        // Too far ahead to be an upcoming flight: last year's.
        assertEquals(LocalDate.of(2025, 11, 22), Bcbp.resolveDate(326, LocalDate.of(2026, 10, 1)))
    }

    @Test
    fun rejectsTextThatIsNotABoardingPass() {
        assertNull(Bcbp.parse("https://example.com/some/qr/code", today))
        assertNull(Bcbp.parse("M1TOO SHORT", today))
        assertNull(Bcbp.parse("M1" + "X".padEnd(20) + "E" + "ABC123 12345678901234567890123456789000", today))
    }

    @Test
    fun toleratesATruncatedConditionalSection() {
        val base = "M1" + "DOE/JOHN".padEnd(20) + "E" +
            leg("PNR1234", "LHR", "JFK", "BA", "0117", "200", "W", "045K", "0090")
        // Claims 30 chars of conditional data but carries only six.
        val p = Bcbp.parse(base + "1E" + ">60010", today)!!
        assertEquals("BA117", p.legs[0].flightNumber)
        assertEquals("premium", p.legs[0].seatClass)
        assertEquals("", p.legs[0].ticketNumber)
    }
}
