package com.roamly.data.prefs

/**
 * The map options that change what the map draws, mirroring the web sidebar's
 * groups: **map display** (basemap is stored on its own, plus fog of war),
 * **track** (transport filter, filled gaps, hide outliers, hide track) and
 * **extra data** (my places). Defaults match the web's: filled gaps shown,
 * outliers hidden, everything else off. Per device, like the basemap.
 */
data class MapLayers(
    val fog: Boolean = false,
    val filledGaps: Boolean = true,
    val hideSuspect: Boolean = true,
    val hideTrack: Boolean = false,
    val places: Boolean = false,
    /** Transport modes drawn; [TRANSPORT_MODES] = no filter. "none" = not yet classified. */
    val modes: Set<String> = TRANSPORT_MODES.toSet(),
    /** Which groups of the options sheet are expanded. */
    val openDisplay: Boolean = true,
    val openTrack: Boolean = false,
    val openExtra: Boolean = false,
) {
    val modeFilterActive: Boolean get() = !modes.containsAll(TRANSPORT_MODES)

    /** How many toggles in each group are on, for the group heads' "n on". */
    val displayOn: Int get() = listOf(fog).count { it }
    val trackOn: Int get() = listOf(modeFilterActive, filledGaps, hideSuspect, hideTrack).count { it }
    val extraOn: Int get() = listOf(places).count { it }

    /** Whether a point with this server transport mode passes the filter. */
    fun showsMode(mode: String?): Boolean = (mode?.trim().orEmpty().ifEmpty { "none" }) in modes

    fun encode(): String = listOf(
        "fog" to fog, "gaps" to filledGaps, "suspect" to hideSuspect, "track" to hideTrack,
        "places" to places, "od" to openDisplay, "ot" to openTrack, "oe" to openExtra,
    ).joinToString(";") { (k, v) -> "$k=${if (v) 1 else 0}" } + ";modes=" + TRANSPORT_MODES.filter { it in modes }.joinToString(",")

    companion object {
        val TRANSPORT_MODES = listOf("walk", "cycle", "vehicle", "plane", "still", "none")

        /** Tolerant: unknown keys are ignored and missing ones keep their defaults. */
        fun decode(raw: String?): MapLayers {
            if (raw.isNullOrBlank()) return MapLayers()
            val kv = raw.split(';').mapNotNull { part ->
                val i = part.indexOf('=')
                if (i <= 0) null else part.substring(0, i) to part.substring(i + 1)
            }.toMap()
            val d = MapLayers()
            fun flag(k: String, def: Boolean) = kv[k]?.let { it == "1" } ?: def
            val modes = kv["modes"]?.split(',')?.map { it.trim() }?.filter { it in TRANSPORT_MODES }?.toSet()
            return MapLayers(
                fog = flag("fog", d.fog),
                filledGaps = flag("gaps", d.filledGaps),
                hideSuspect = flag("suspect", d.hideSuspect),
                hideTrack = flag("track", d.hideTrack),
                places = flag("places", d.places),
                modes = modes ?: d.modes,
                openDisplay = flag("od", d.openDisplay),
                openTrack = flag("ot", d.openTrack),
                openExtra = flag("oe", d.openExtra),
            )
        }
    }
}
