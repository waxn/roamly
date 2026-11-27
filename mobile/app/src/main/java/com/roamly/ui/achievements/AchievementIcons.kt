package com.roamly.ui.achievements

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.BeachAccess
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CellTower
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.ConfirmationNumber
import androidx.compose.material.icons.rounded.DirectionsBike
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Eco
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.GpsFixed
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Hiking
import androidx.compose.material.icons.rounded.Hotel
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LocalBar
import androidx.compose.material.icons.rounded.LocalCafe
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.LocalShipping
import androidx.compose.material.icons.rounded.LocationCity
import androidx.compose.material.icons.rounded.Luggage
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.Park
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Pool
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Rowing
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material.icons.rounded.SortByAlpha
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.Terrain
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material.icons.rounded.WbTwilight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.DirectionsRun
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The server names every badge's icon with a key from the web's line-icon set
 * (`static/tracker/icons.js`); these are the closest Material equivalents. An
 * unknown key draws a trophy, so a badge added server-side never crashes the app.
 */
fun achievementIcon(key: String): ImageVector = when (key) {
    "globe" -> Icons.Rounded.Public
    "flag" -> Icons.Rounded.Flag
    "compass" -> Icons.Rounded.Explore
    "pin" -> Icons.Rounded.Place
    "map" -> Icons.Rounded.Map
    "grid" -> Icons.Rounded.GridView
    "road", "route" -> Icons.Rounded.Route
    "check" -> Icons.Rounded.CheckCircle
    "tick" -> Icons.Rounded.Check
    "truck" -> Icons.Rounded.LocalShipping
    "leaf" -> Icons.Rounded.Eco
    "ruler" -> Icons.Rounded.Straighten
    "gauge" -> Icons.Rounded.Speed
    "repeat" -> Icons.Rounded.Repeat
    "feet", "walk" -> Icons.Rounded.DirectionsWalk
    "run" -> Icons.Rounded.DirectionsRun
    "bike" -> Icons.Rounded.DirectionsBike
    "cart" -> Icons.Rounded.ShoppingCart
    "coffee" -> Icons.Rounded.LocalCafe
    "link" -> Icons.Rounded.Link
    "star" -> Icons.Rounded.Star
    "sparkles" -> Icons.Rounded.AutoAwesome
    "calendar" -> Icons.Rounded.CalendarMonth
    "bed" -> Icons.Rounded.Hotel
    "sunrise" -> Icons.Rounded.WbTwilight
    "moon" -> Icons.Rounded.NightsStay
    "wind" -> Icons.Rounded.Air
    "passport" -> Icons.Rounded.Badge
    "utensils" -> Icons.Rounded.Restaurant
    "mountain" -> Icons.Rounded.Terrain
    "trend" -> Icons.Rounded.TrendingUp
    "car" -> Icons.Rounded.DirectionsCar
    "crown", "medal", "trophy" -> Icons.Rounded.EmojiEvents
    "suitcase" -> Icons.Rounded.Luggage
    "clock" -> Icons.Rounded.Schedule
    "home" -> Icons.Rounded.Home
    "letters" -> Icons.Rounded.SortByAlpha
    "users" -> Icons.Rounded.Group
    "undo" -> Icons.Rounded.Undo
    "tree" -> Icons.Rounded.Park
    "landmark" -> Icons.Rounded.AccountBalance
    "umbrella" -> Icons.Rounded.BeachAccess
    "plane" -> Icons.Rounded.Flight
    "ticket" -> Icons.Rounded.ConfirmationNumber
    "cloud" -> Icons.Rounded.Cloud
    "bolt" -> Icons.Rounded.Bolt
    "tower" -> Icons.Rounded.CellTower
    "book" -> Icons.Rounded.AutoStories
    "pulse" -> Icons.Rounded.MonitorHeart
    "flame" -> Icons.Rounded.LocalFireDepartment
    "thermo" -> Icons.Rounded.Thermostat
    "dumbbell" -> Icons.Rounded.FitnessCenter
    "crosshair" -> Icons.Rounded.GpsFixed
    "building" -> Icons.Rounded.LocationCity
    "swim" -> Icons.Rounded.Pool
    "yoga" -> Icons.Rounded.SelfImprovement
    "row" -> Icons.Rounded.Rowing
    "hike" -> Icons.Rounded.Hiking
    "glass" -> Icons.Rounded.LocalBar
    "fuel" -> Icons.Rounded.LocalGasStation
    "wave" -> Icons.Rounded.Waves
    else -> Icons.Rounded.EmojiEvents
}

/** The badge group's own icon, for its filter chip and folded heading. */
fun achievementGroupIcon(group: String): ImageVector = when (group) {
    "explorer" -> Icons.Rounded.Explore
    "roads" -> Icons.Rounded.Route
    "distance" -> Icons.Rounded.Speed
    "regulars" -> Icons.Rounded.LocalCafe
    "rhythm" -> Icons.Rounded.Schedule
    "bigdays" -> Icons.Rounded.Air
    "climber" -> Icons.Rounded.Terrain
    "journeys" -> Icons.Rounded.Luggage
    "globe" -> Icons.Rounded.Public
    "collector" -> Icons.Rounded.SortByAlpha
    "nature" -> Icons.Rounded.Park
    "health" -> Icons.Rounded.MonitorHeart
    "sky" -> Icons.Rounded.Flight
    "years" -> Icons.Rounded.CalendarMonth
    else -> Icons.Rounded.EmojiEvents
}
