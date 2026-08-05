package com.roamly.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.roamly.ui.ask.AskViewModel
import com.roamly.ui.auth.AuthViewModel
import com.roamly.ui.auth.LoginScreen
import com.roamly.ui.groups.GroupsScreen
import com.roamly.ui.journals.JournalsScreen
import com.roamly.ui.map.MapScreen
import com.roamly.ui.map.MapViewModel
import com.roamly.ui.pals.PalDetailScreen
import com.roamly.ui.settings.SettingsScreen
import com.roamly.ui.search.SearchTabScreen
import com.roamly.ui.stats.StatsScreen
import com.roamly.ui.trips.TripDetailScreen
import com.roamly.ui.update.UpdateBanner
import com.roamly.ui.update.UpdateViewModel

sealed class Screen(val route: String, val label: String) {
    object Map        : Screen("map",         "Map")
    object Adventures : Screen("adventures",  "Trips")   // shorter label to avoid truncation
    object Search     : Screen("search",      "Search")
    object Journal    : Screen("journal",     "Journal")
    object Stats      : Screen("stats",       "Stats")
    object Settings   : Screen("settings",    "Settings")
    object Login      : Screen("login",       "Login")
    object TripDetail : Screen("trips/{tripId}", "Trip")
    object PalDetail  : Screen("pals/{palId}",  "Pal")
}

// Search sits in the middle slot always — it hosts history search, plus the AI
// Ask chat when the user has a provider configured (see SearchTabScreen).
private val bottomNavItems: List<Screen> =
    listOf(Screen.Map, Screen.Adventures, Screen.Search, Screen.Journal, Screen.Stats, Screen.Settings)

private fun iconFor(screen: Screen): ImageVector = when (screen) {
    Screen.Map        -> Icons.Rounded.Map
    Screen.Adventures -> Icons.Rounded.Explore
    Screen.Search     -> Icons.Rounded.Search
    Screen.Journal    -> Icons.Rounded.AutoStories
    Screen.Stats      -> Icons.Rounded.BarChart
    else              -> Icons.Rounded.Settings
}

@Composable
fun RoamlyNavHost() {
    val navController = rememberNavController()
    val authViewModel: AuthViewModel = hiltViewModel()
    val mapViewModel: MapViewModel   = hiltViewModel()
    val updateViewModel: UpdateViewModel = hiltViewModel()
    val askViewModel: AskViewModel = hiltViewModel()
    val isLoggedIn by authViewModel.isLoggedIn.collectAsState()

    // Check for a newer sideloaded build once logged in (throttled to ~24h).
    LaunchedEffect(isLoggedIn) {
        if (isLoggedIn == true) updateViewModel.checkOnLaunch()
    }

    var splashDone by rememberSaveable { mutableStateOf(false) }
    if (!splashDone || isLoggedIn == null) {
        SplashScreen(onFinished = { splashDone = true })
        return
    }

    val initialRoute = if (isLoggedIn == true) Screen.Map.route else Screen.Login.route

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    // If the session goes invalid while the app is open — the SessionGuard
    // interceptor clears it after the server bounces an API call to the login
    // page — isLoggedIn flips to false. Route back to login so the user
    // re-authenticates instead of sitting on a screen that can't load anything.
    LaunchedEffect(isLoggedIn) {
        if (isLoggedIn == false && currentDestination != null &&
            currentDestination?.route != Screen.Login.route
        ) {
            navController.navigate(Screen.Login.route) { popUpTo(0) { inclusive = true } }
        }
    }

    val showBottomBar = currentDestination?.route != Screen.Login.route &&
            currentDestination?.route != Screen.TripDetail.route &&
            currentDestination?.route != Screen.PalDetail.route

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 3.dp,
                ) {
                    bottomNavItems.forEach { screen ->
                        val selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(iconFor(screen), contentDescription = screen.label) },
                            label = { Text(screen.label, style = MaterialTheme.typography.labelSmall) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
      Column(modifier = Modifier.padding(innerPadding)) {
        val updateState by updateViewModel.state.collectAsState()
        NavHost(
            navController = navController,
            startDestination = initialRoute,
            modifier = Modifier.weight(1f),
            enterTransition    = { fadeIn(tween(100)) },
            exitTransition     = { fadeOut(tween(100)) },
            popEnterTransition = { fadeIn(tween(100)) },
            popExitTransition  = { fadeOut(tween(100)) },
        ) {
            composable(Screen.Login.route) {
                LoginScreen(
                    viewModel = authViewModel,
                    onLoggedIn = {
                        navController.navigate(Screen.Map.route) {
                            popUpTo(Screen.Login.route) { inclusive = true }
                        }
                    }
                )
            }
            composable(Screen.Map.route) { MapScreen(viewModel = mapViewModel) }
            composable(Screen.Adventures.route) {
                GroupsScreen(
                    onTripClick = { id -> navController.navigate("trips/$id") },
                    onPalClick  = { id -> navController.navigate("pals/$id") }
                )
            }
            composable(
                route = Screen.TripDetail.route,
                arguments = listOf(navArgument("tripId") { type = NavType.IntType })
            ) { back ->
                TripDetailScreen(tripId = back.arguments!!.getInt("tripId"), onBack = { navController.popBackStack() })
            }
            composable(
                route = Screen.PalDetail.route,
                arguments = listOf(navArgument("palId") { type = NavType.IntType })
            ) { back ->
                PalDetailScreen(palId = back.arguments!!.getInt("palId"), onBack = { navController.popBackStack() })
            }
            composable(Screen.Search.route) {
                val toMap = {
                    navController.navigate(Screen.Map.route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
                SearchTabScreen(
                    askViewModel = askViewModel,
                    onOpenMapDate = { dateStr -> mapViewModel.navigateToDate(dateStr); toMap() },
                    onFocusMap = { lat, lng -> mapViewModel.focusOn(lat, lng, zoom = 16.0); toMap() },
                )
            }
            composable(Screen.Journal.route) {
                JournalsScreen(
                    onOpenMap = { dateStr ->
                        mapViewModel.navigateToDate(dateStr)
                        navController.navigate(Screen.Map.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
            composable(Screen.Stats.route)    { StatsScreen(onNavigateToMap = { dateStr -> mapViewModel.navigateToDate(dateStr); navController.navigate(Screen.Map.route) { popUpTo(navController.graph.findStartDestination().id) { saveState = true }; launchSingleTop = true; restoreState = true } }) }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    onLoggedOut = {
                        navController.navigate(Screen.Login.route) { popUpTo(0) { inclusive = true } }
                    },
                    updateViewModel = updateViewModel,
                )
            }
        }
        // Sits just above the bottom navbar so it never hides under the status
        // bar and shortens content instead of overlapping it.
        if (isLoggedIn == true && showBottomBar) {
            UpdateBanner(
                state = updateState,
                onUpdate = updateViewModel::downloadAndInstall,
                onDismiss = updateViewModel::dismissBanner,
            )
        }
      }
    }
}
