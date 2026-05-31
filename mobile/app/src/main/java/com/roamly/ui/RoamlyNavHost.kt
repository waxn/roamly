package com.roamly.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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
import com.roamly.ui.groups.GroupsScreen
import com.roamly.ui.map.MapScreen
import com.roamly.ui.pals.PalDetailScreen
import com.roamly.ui.settings.SettingsScreen
import com.roamly.ui.stats.StatsScreen
import com.roamly.ui.trips.TripDetailScreen

sealed class Screen(val route: String, val label: String) {
    object Map : Screen("map", "Map")
    object Adventures : Screen("adventures", "Adventures")
    object Stats : Screen("stats", "Stats")
    object Settings : Screen("settings", "Settings")
    object Login : Screen("login", "Login")
    object TripDetail : Screen("trips/{tripId}", "Trip")
    object PalDetail : Screen("pals/{palId}", "Pal")
}

private val bottomNavItems = listOf(Screen.Map, Screen.Adventures, Screen.Stats, Screen.Settings)

@Composable
fun RoamlyNavHost() {
    val navController = rememberNavController()
    val authViewModel: AuthViewModel = hiltViewModel()
    val isLoggedIn by authViewModel.isLoggedIn.collectAsState()
    val initialRoute = when (isLoggedIn) {
        null -> null
        true -> Screen.Map.route
        false -> Screen.Login.route
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val showBottomBar = initialRoute != null &&
            currentDestination?.route != Screen.Login.route &&
            currentDestination?.route != Screen.TripDetail.route &&
            currentDestination?.route != Screen.PalDetail.route

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp
                ) {
                    bottomNavItems.forEach { screen ->
                        NavigationBarItem(
                            icon = {
                                Icon(
                                    imageVector = when (screen) {
                                        Screen.Map -> Icons.Rounded.Map
                                        Screen.Adventures -> Icons.Rounded.Explore
                                        Screen.Stats -> Icons.Rounded.BarChart
                                        else -> Icons.Rounded.Settings
                                    },
                                    contentDescription = screen.label,
                                    modifier = Modifier.size(24.dp)
                                )
                            },
                            label = { Text(screen.label.lowercase()) },
                            selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                indicatorColor = Color.Transparent
                            ),
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
        val destination = initialRoute
        if (destination == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        NavHost(
            navController = navController,
            startDestination = destination,
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
            composable(Screen.Adventures.route) {
                GroupsScreen(
                    onTripClick = { id -> navController.navigate("trips/$id") },
                    onPalClick = { id -> navController.navigate("pals/$id") }
                )
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
            composable(
                route = Screen.PalDetail.route,
                arguments = listOf(navArgument("palId") { type = NavType.IntType })
            ) { backStackEntry ->
                PalDetailScreen(
                    palId = backStackEntry.arguments!!.getInt("palId"),
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Screen.Stats.route) { StatsScreen() }
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
