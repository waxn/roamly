package com.roamly.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
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
import com.roamly.ui.theme.Clay
import com.roamly.ui.theme.claySoftShadow
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

private fun iconFor(screen: Screen): ImageVector = when (screen) {
    Screen.Map -> Icons.Rounded.Map
    Screen.Adventures -> Icons.Rounded.Explore
    Screen.Stats -> Icons.Rounded.BarChart
    else -> Icons.Rounded.Settings
}

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
        containerColor = Color.Transparent,
        bottomBar = {
            if (showBottomBar) {
                ClayBottomBar(
                    current = currentDestination?.hierarchy,
                    onSelect = { screen ->
                        navController.navigate(screen.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
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
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
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

/** A floating, puffy clay navigation bar. The selected tab inflates into a
 *  gradient pill with its label; the others stay compact icons. */
@Composable
private fun ClayBottomBar(
    current: Sequence<androidx.navigation.NavDestination>?,
    onSelect: (Screen) -> Unit,
) {
    val clay = Clay.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .claySoftShadow(28.dp, clay.shadowDark, clay.shadowLight, depth = 18.dp)
                .background(
                    Brush.verticalGradient(listOf(clay.surfaceTop, clay.surfaceBottom)),
                    RoundedCornerShape(28.dp),
                )
                .padding(horizontal = 10.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            bottomNavItems.forEach { screen ->
                val selected = current?.any { it.route == screen.route } == true
                NavPill(
                    screen = screen,
                    selected = selected,
                    modifier = Modifier.weight(if (selected) 1.4f else 1f),
                    onClick = { onSelect(screen) },
                )
            }
        }
    }
}

@Composable
private fun NavPill(
    screen: Screen,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val clay = Clay.colors
    val height by animateDpAsState(if (selected) 48.dp else 46.dp, spring(), label = "pillH")
    val iconColor by animateColorAsState(
        if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "pillIcon",
    )
    val shape = RoundedCornerShape(20.dp)
    val bg = if (selected) Brush.verticalGradient(clay.primaryGradient)
    else Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))

    Row(
        modifier = modifier
            .height(height)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .then(
                if (selected) Modifier.claySoftShadow(20.dp, clay.primaryGradient.last().copy(alpha = 0.4f), Color.Transparent, depth = 8.dp, drawLight = false)
                else Modifier
            )
            .background(bg, shape),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = iconFor(screen),
            contentDescription = screen.label,
            tint = iconColor,
            modifier = Modifier.size(22.dp),
        )
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Text(
                screen.label,
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
