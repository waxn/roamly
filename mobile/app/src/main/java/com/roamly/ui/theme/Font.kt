package com.roamly.ui.theme

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.roamly.R

/** The one typeface: Figtree (variable weight, OFL), from display figures down to body copy. */
@OptIn(ExperimentalTextApi::class)
val FigtreeFamily = FontFamily(
    listOf(400, 500, 600, 700).map { w ->
        Font(
            R.font.figtree_variable, FontWeight(w),
            variationSettings = FontVariation.Settings(FontVariation.weight(w)),
        )
    }
)

/** Eyebrows, coordinates and timestamps. */
val PlexMonoFamily = FontFamily(
    Font(R.font.plex_mono_regular, FontWeight.Normal),
    Font(R.font.plex_mono_semibold, FontWeight.SemiBold),
)
