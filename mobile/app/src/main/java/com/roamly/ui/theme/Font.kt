package com.roamly.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.roamly.R

/** Display serif — headings/titles, ported from the web's --font-display. */
val FrauncesFamily = FontFamily(
    Font(R.font.fraunces_regular, FontWeight.Normal),
    Font(R.font.fraunces_semibold, FontWeight.SemiBold),
    Font(R.font.fraunces_bold, FontWeight.Bold),
)

/** Body sans — ported from the web's --font-body. */
val PlexSansFamily = FontFamily(
    Font(R.font.plex_sans_regular, FontWeight.Normal),
    Font(R.font.plex_sans_medium, FontWeight.Medium),
    Font(R.font.plex_sans_semibold, FontWeight.SemiBold),
    Font(R.font.plex_sans_bold, FontWeight.Bold),
)

/** Numerals/coordinates/micro-labels — ported from the web's --font-mono. */
val PlexMonoFamily = FontFamily(
    Font(R.font.plex_mono_regular, FontWeight.Normal),
    Font(R.font.plex_mono_semibold, FontWeight.SemiBold),
)
