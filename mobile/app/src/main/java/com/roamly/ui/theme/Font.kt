package com.roamly.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.roamly.R

/** The one typeface: IBM Plex Sans, from display figures down to body copy. */
val PlexSansFamily = FontFamily(
    Font(R.font.plex_sans_regular, FontWeight.Normal),
    Font(R.font.plex_sans_medium, FontWeight.Medium),
    Font(R.font.plex_sans_semibold, FontWeight.SemiBold),
    Font(R.font.plex_sans_bold, FontWeight.Bold),
)

/** Eyebrows, coordinates and timestamps. */
val PlexMonoFamily = FontFamily(
    Font(R.font.plex_mono_regular, FontWeight.Normal),
    Font(R.font.plex_mono_semibold, FontWeight.SemiBold),
)
