package com.roamly.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.roamly.ui.auth.AuthViewModel
import com.roamly.ui.auth.LoginScreen
import com.roamly.ui.map.MapScreen
import com.roamly.ui.pals.PalDetailScreen
import com.roamly.ui.pals.PalsScreen
import com.roamly.ui.settings.SettingsScreen
import com.roamly.ui.trips.TripDetailScreen
import com.roamly.ui.trips.TripsScreen

sealed class Screen(val route: String, val label: String) {
    object Map : Screen("map", "Map")
    object Trips : Screen("trips", "Trips")
    object Pals : Screen("pals", "Pals")
    object Settings : Screen("settings", "Settings")
    object Login : Screen("login", "Login")
    object TripDetail : Screen("trips/{tripId}", "Trip")
    object PalDetail : Screen("pals/{palId}", "Pal")
}

private val bottomNavItems = listOf(Screen.Map, Screen.Trips, Screen.Pals, Screen.Settings)

@Composable
fun RoamlyNavHost() {
    val navController = rememberNavController()
    val authViewModel: AuthViewModel = hiltViewModel()

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val showBottomBar = currentDestination?.route != Screen.Login.route

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomNavItems.forEach { screen ->
                        NavigationBarItem(
                            icon = {
                                Icon(
                                    imageVector = when (screen) {
                                        Screen.Map -> Icons.Filled.Map
                                        Screen.Trips -> Icons.Filled.Route
                                        Screen.Pals -> Icons.Filled.People
                                        else -> Icons.Filled.Settings
                                    },
                                    contentDescription = screen.label
                                )
                            },
                            label = { Text(screen.label) },
                            selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Login.route,
            modifier = Modifier.padding(innerPadding)
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
            composable(Screen.Map.route) { MapScreen() }
            composable(Screen.Trips.route) {
                TripsScreen(onTripClick = { id -> navController.navigate("trips/$id") })
            }
            composable(
                route = Screen.TripDetail.route,
                arguments = listOf(navArgument("tripId") { type = NavType.IntType })
            ) { backStackEntry ->
                TripDetailScreen(
                    tripId = backStackEntry.arguments!!.getInt("tripId"),
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Screen.Pals.route) {
                PalsScreen(onPalClick = { id -> navController.navigate("pals/$id") })
            }
            composable(
                route = Screen.PalDetail.route,
                arguments = listOf(navArgument("palId") { type = NavType.IntType })
            ) { backStackEntry ->
                PalDetailScreen(
                    palId = backStackEntry.arguments!!.getInt("palId"),
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    onLoggedOut = {
                        navController.navigate(Screen.Login.route) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                )
            }
        }
    }
}
