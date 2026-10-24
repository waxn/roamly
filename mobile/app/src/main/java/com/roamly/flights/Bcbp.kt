package com.roamly.flights

import java.time.LocalDate

/**
 * Parser for the IATA Bar Coded Boarding Pass (BCBP, Resolution 792) — the
 * text inside the PDF417 / Aztec / QR code on nearly every boarding pass,
 * paper or phone.
 *
 * The layout is fixed-width:
 *
 *  - Mandatory unique (23 chars): format code `M`, number of legs, passenger
 *    name (20), electronic-ticket indicator.
 *  - Per leg, mandatory repeated (35 chars): booking reference (7), from (3),
 *    to (3), operating carrier (3), flight number (5), day of year (3), cabin
 *    (1), seat (4), check-in sequence (5), passenger status (1) — then a
 *    2-hex-digit size and a variable section of that size.
 *  - The first leg's variable section may open with the unique conditional
 *    block (`>`, version, its own 2-hex size); every leg's then carries a
 *    repeated conditional block (2-hex size) with the ticket number and the
 *    frequent-flyer number, followed by airline-private data.
 *
 * Everything past the mandatory fields is optional and airlines truncate it
 * freely, so the parser reads each section only as far as it exists and never
 * throws: a pass it cannot read at all returns null.
 *
 * The pass carries the flight date as a day of the year with **no year**;
 * [resolveDate] picks the matching date nearest today.
 */
object Bcbp {

    data class Leg(
        val bookingRef: String,
        val from: String,
        val to: String,
        val carrier: String,
        /** Normalised: `UA 0123 ` -> `UA123`. */
        val flightNumber: String,
        val dayOfYear: Int?,
        val date: LocalDate?,
        val cabin: String,
        /** Roamly's seat_class value, or "" when the cabin code says nothing reliable. */
        val seatClass: String,
        val seat: String,
        val checkinSeq: String,
        val ticketNumber: String,
        val frequentFlyer: String,
    )

    data class Pass(
        val raw: String,
        /** As printed: `DESMARAIS/LUC`. */
        val nameRaw: String,
        /** Display form: `Luc Desmarais`. */
        val passengerName: String,
        val legs: List<Leg>,
    )

    /** Parse [text], or null when it is not a BCBP payload. */
    fun parse(text: String, today: LocalDate = LocalDate.now()): Pass? {
        val raw = text.trimEnd('\n', '\r')
        if (raw.length < 58 || raw[0] != 'M' || !raw[1].isDigit()) return null
        val legCount = raw[1].digitToInt()
        if (legCount !in 1..4) return null

        val nameRaw = raw.substring(2, 22).trim()
        var pos = 23
        val legs = mutableListOf<Leg>()
        for (i in 0 until legCount) {
            if (pos + 35 > raw.length) break
            val m = raw.substring(pos, pos + 35)
            val varSize = hex(raw, pos + 35) ?: 0
            val varStart = pos + 37
            val variable = sub(raw, varStart, varStart + varSize)
            pos = varStart + varSize

            val carrier = m.substring(13, 16).trim()
            val doy = m.substring(21, 24).trim().toIntOrNull()?.takeIf { it in 1..366 }
            val cabin = m.substring(24, 25).trim()

            // Conditional data. The unique block only ever opens the first leg.
            var rest = variable
            if (i == 0 && rest.startsWith(">") && rest.length >= 4) {
                val uniqueSize = hex(rest, 2) ?: 0
                rest = sub(rest, 4 + uniqueSize, rest.length)
            }
            var ticket = ""
            var ff = ""
            val repSize = hex(rest, 0)
            if (repSize != null) {
                val r = sub(rest, 2, 2 + repSize)
                val airlineNum = sub(r, 0, 3).trim()
                val serial = sub(r, 3, 13).trim()
                if (airlineNum.all { it.isDigit() } && serial.all { it.isDigit() } &&
                    airlineNum.length == 3 && serial.isNotEmpty()) {
                    ticket = airlineNum + serial
                }
                val ffAirline = sub(r, 18, 21).trim()
                val ffNumber = sub(r, 21, 37).trim()
                if (ffNumber.isNotEmpty()) ff = (ffAirline + " " + ffNumber).trim()
            }

            legs += Leg(
                bookingRef = m.substring(0, 7).trim(),
                from = m.substring(7, 10).trim(),
                to = m.substring(10, 13).trim(),
                carrier = carrier,
                flightNumber = carrier + normaliseNumber(m.substring(16, 21)),
                dayOfYear = doy,
                date = doy?.let { resolveDate(it, today) },
                cabin = cabin,
                seatClass = seatClassFor(cabin),
                seat = normaliseNumber(m.substring(25, 29)),
                checkinSeq = normaliseNumber(m.substring(29, 34)),
                ticketNumber = ticket,
                frequentFlyer = ff,
            )
        }
        if (legs.isEmpty()) return null
        if (legs.any { !it.from.isAirportCode() || !it.to.isAirportCode() }) return null
        return Pass(raw = raw, nameRaw = nameRaw, passengerName = displayName(nameRaw), legs = legs)
    }

    /**
     * The date with this day-of-year nearest [today], looking back up to 330
     * days and ahead up to 35: passes are scanned around the flight, and an
     * old pass found in a screenshot is far likelier than one for next year.
     */
    fun resolveDate(dayOfYear: Int, today: LocalDate): LocalDate? {
        val candidates = (today.year - 1..today.year + 1).mapNotNull { y ->
            runCatching { LocalDate.ofYearDay(y, dayOfYear) }.getOrNull()
                ?.takeIf { it.year == y }
        }
        val inWindow = candidates.filter {
            val d = it.toEpochDay() - today.toEpochDay()
            d in -330..35
        }
        return (inWindow.ifEmpty { candidates })
            .minByOrNull { kotlin.math.abs(it.toEpochDay() - today.toEpochDay()) }
    }

    /**
     * Cabin codes are each airline's own, so only the near-universal ones are
     * mapped and everything unfamiliar is left for the user to set.
     */
    fun seatClassFor(cabin: String): String = when (cabin.uppercase()) {
        "F", "A", "P" -> "first"
        "J", "C", "D", "I", "Z" -> "business"
        "W", "E" -> "premium"
        "Y", "B", "H", "K", "L", "M", "N", "Q", "S", "T", "V", "X", "G", "O", "U" -> "economy"
        else -> ""
    }

    /** `0834 ` -> `834`, `0123A` -> `123A`, `001A` -> `1A`. */
    private fun normaliseNumber(s: String): String {
        val t = s.trim()
        val stripped = t.trimStart('0')
        return when {
            stripped.isEmpty() -> if (t.isEmpty()) "" else "0"
            // Never strip a zero that leaves a bare letter (`0A`): keep it whole.
            stripped[0].isLetter() -> t
            else -> stripped
        }
    }

    /** `DESMARAIS/LUC MR` -> `Luc Desmarais`. */
    fun displayName(raw: String): String {
        val parts = raw.split('/', limit = 2)
        val surname = parts[0].trim()
        var given = parts.getOrNull(1)?.trim().orEmpty()
        for (t in listOf("MR", "MRS", "MS", "MISS", "MSTR", "DR", "PROF")) {
            if (given.endsWith(" $t")) given = given.removeSuffix(" $t").trim()
            else if (given == t) given = ""
        }
        fun cap(w: String) = w.lowercase().split(' ', '-').joinToString(" ") { p ->
            p.replaceFirstChar { it.uppercase() }
        }
        return listOf(cap(given), cap(surname)).filter { it.isNotBlank() }.joinToString(" ")
    }

    private fun String.isAirportCode() = length == 3 && all { it.isLetter() }

    private fun sub(s: String, start: Int, end: Int): String {
        val a = start.coerceIn(0, s.length)
        val b = end.coerceIn(a, s.length)
        return s.substring(a, b)
    }

    private fun hex(s: String, at: Int): Int? =
        if (at + 2 <= s.length) s.substring(at, at + 2).toIntOrNull(16) else null
}
