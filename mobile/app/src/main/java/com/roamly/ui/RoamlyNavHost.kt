package com.roamly.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Watch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
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
import com.roamly.ui.journals.JournalsViewModel
import com.roamly.ui.map.MapScreen
import com.roamly.ui.map.MapViewModel
import com.roamly.ui.settings.SettingsScreen
import com.roamly.ui.search.SearchTabScreen
import com.roamly.ui.stats.StatsScreen
import com.roamly.ui.data.DataHubScreen
import com.roamly.ui.data.DataPage
import com.roamly.ui.flights.FlightEditScreen
import com.roamly.ui.flights.FlightsScreen
import com.roamly.ui.flights.FlightsViewModel
import com.roamly.ui.flights.ScanPassScreen
import com.roamly.ui.health.HealthScreen
import com.roamly.ui.settings.LocationTableScreen
import com.roamly.ui.trips.TripsTabScreen
import com.roamly.flights.IncomingShare
import com.roamly.ui.stats.StatsViewModel
import com.roamly.ui.record.RecordScreen
import com.roamly.ui.tracker.TrackerScreen
import com.roamly.ui.tripblog.TripBlogScreen
import com.roamly.ui.tripblog.TripBlogViewModel
import com.roamly.ui.tripblog.TripComposeScreen
import com.roamly.ui.trips.TripDetailScreen
import com.roamly.ui.trips.TripsViewModel
import com.roamly.ui.update.UpdateBanner
import com.roamly.ui.update.UpdateViewModel

sealed class Screen(val route: String, val label: String) {
    object Map        : Screen("map",         "Map")
    object Adventures : Screen("adventures",  "Trips")   // shorter label to avoid truncation
    object Search     : Screen("search",      "Search")
    // Stats, Flights, Health and Location data, behind chips (DataHubScreen).
    // Journal is no longer a tab of its own: it sits under Trips.
    object Data       : Screen("data",        "Data")
    object Settings   : Screen("settings",    "Settings")
    object Login      : Screen("login",       "Login")
    object TripDetail : Screen("trips/{tripId}", "Trip")
    // A real route with no tab, like TripDetail: the bar is full at six, and
    // Record is reached from the map where you already are before heading out.
    // Being a route rather than a swap-in-place boolean also means system back
    // just works, with no BackHandler — recording continues in the foreground
    // service either way, which the ongoing notification says.
    object Record     : Screen("record",      "Record")
    // The Roamly Tracker (BLE). Only in the bar once a tracker is paired —
    // before that it is reached from Settings → Roamly Tracker to pair one.
    object Tracker    : Screen("tracker",     "Tracker")
    // Boarding-pass scanning and the flight editor: routes with no tab, like
    // Record. `uri` is a file shared into Roamly (IncomingShare).
    object FlightScan : Screen("flights/scan?uri={uri}", "Scan")
    object FlightEdit : Screen("flights/edit", "Flight")
    // The featured trip blog. Only in the bar while a trip is featured (set
    // on the web, or by joining one); the composer is a route with no tab.
    // "Blog", not "Trip": the Adventures tab is already labelled "Trips".
    object TripBlog   : Screen("tripblog", "Blog")
    object TripCompose : Screen("tripblog/compose", "New post")
}

// Search sits in the middle slot always — it hosts history search, plus the AI
// Ask chat when the user has a provider configured (see SearchTabScreen).
// Five tabs: Trips hosts Journal (TripsTabScreen) and Data hosts Stats,
// Flights, Health and Location data (DataHubScreen), so nothing is buried in
// Settings any more and the bar fits a phone even with the Tracker tab added.
private val advancedNavItems: List<Screen> =
    listOf(Screen.Map, Screen.Adventures, Screen.Search, Screen.Data, Screen.Settings)

// Simple Mode: a non-technical family member gets just enough to see the
// family map and reach Settings (where Family Circle itself lives, alongside
// the toggle back to Advanced Mode) — none of Trips/Search/Journal/Stats.
// Family Circle has no bottom-nav slot of its own even here, the same
// reasoning Screen.Record already uses: it's reached from Settings instead.
private val simpleNavItems: List<Screen> =
    listOf(Screen.Map, Screen.Settings)

private fun iconFor(screen: Screen): ImageVector = when (screen) {
    Screen.Map        -> Icons.Rounded.Map
    Screen.Adventures -> Icons.Rounded.Explore
    Screen.Search     -> Icons.Rounded.Search
    Screen.Data       -> Icons.Rounded.Insights
    Screen.Tracker    -> Icons.Rounded.Watch
    Screen.TripBlog   -> Icons.Rounded.Public
    else              -> Icons.Rounded.Settings
}

@Composable
fun RoamlyNavHost() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val authViewModel: AuthViewModel = hiltViewModel()
    val mapViewModel: MapViewModel   = hiltViewModel()
    val updateViewModel: UpdateViewModel = hiltViewModel()
    val askViewModel: AskViewModel = hiltViewModel()
    // Activity-scoped like MapViewModel: the scanner hands its draft to the
    // editor through it, across two routes. Only one-shot loads in its init
    // (none, in fact), so the rule about continuous collectors holds.
    val flightsViewModel: FlightsViewModel = hiltViewModel()

    // The Activity's ViewModelStoreOwner, captured out here where
    // LocalViewModelStoreOwner still resolves to the Activity rather than to a
    // NavBackStackEntry. Each bottom-nav tab below passes this to
    // hiltViewModel() instead of taking the default.
    //
    // Why: a destination's default owner is its NavBackStackEntry, and the tab
    // navigation pops with saveState=true, which clears that entry's
    // ViewModelStore. So every tab switch destroyed the tab's ViewModel and the
    // next visit re-ran its init from scratch — a synchronous Gson read off disk
    // on the main thread plus a fresh round of network calls, with a spinner in
    // between. Against the Activity's store they survive the switch and a
    // revisit paints what it already had.
    //
    // Resolving them here rather than constructing them here is deliberate:
    // hiltViewModel() builds the ViewModel on the spot, and these inits fire
    // network calls immediately — during the splash that means hitting the
    // placeholder base URL before login resolves, the same trap MapViewModel's
    // init comments describe. Passing the owner down keeps construction lazy
    // (first visit to that tab) while keeping the scope wide.
    val activityOwner = checkNotNull(LocalViewModelStoreOwner.current) {
        "No ViewModelStoreOwner — RoamlyNavHost must be hosted by the Activity"
    }
    val isLoggedIn by authViewModel.isLoggedIn.collectAsState()
    val simpleMode by authViewModel.simpleModeEnabled.collectAsState()
    val trackerPaired by authViewModel.trackerPaired.collectAsState()
    val featuredTrip by authViewModel.featuredTripId.collectAsState()
    val bottomNavItems = (if (simpleMode) simpleNavItems else advancedNavItems).let { items ->
        // The trip goes right after Map: following it is the point of the trip.
        if (featuredTrip != null) listOf(items.first(), Screen.TripBlog) + items.drop(1) else items
    }.let { items ->
        if (trackerPaired) items.dropLast(1) + Screen.Tracker + items.last() else items
    }

    // Check for a newer sideloaded build once logged in (throttled to ~24h).
    LaunchedEffect(isLoggedIn) {
        if (isLoggedIn == true) {
            updateViewModel.checkOnLaunch()
            authViewModel.refreshFeaturedTrip()
        }
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
            currentDestination?.route != Screen.Record.route &&
            currentDestination?.route != Screen.FlightScan.route &&
            currentDestination?.route != Screen.FlightEdit.route &&
            currentDestination?.route != Screen.TripCompose.route

    // A boarding pass shared into Roamly opens the scanner on it, once signed in.
    val sharedUri by IncomingShare.uri.collectAsState()
    LaunchedEffect(sharedUri, isLoggedIn, currentDestination) {
        val uri = sharedUri ?: return@LaunchedEffect
        if (isLoggedIn != true || currentDestination == null ||
            currentDestination?.route == Screen.Login.route) return@LaunchedEffect
        IncomingShare.consume()
        navController.navigate("flights/scan?uri=" + android.net.Uri.encode(uri.toString()))
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBottomBar) {
                val borderColor = MaterialTheme.colorScheme.outline
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp,
                    modifier = Modifier.drawBehind {
                        drawLine(
                            color = borderColor,
                            start = Offset(0f, 0f),
                            end = Offset(size.width, 0f),
                            strokeWidth = 1.dp.toPx(),
                        )
                    },
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
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                // Flat color shift on selection instead of the default M3 pill indicator.
                                indicatorColor = Color.Transparent,
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
            composable(Screen.Map.route) {
                MapScreen(
                    viewModel = mapViewModel,
                    onRecord = { navController.navigate(Screen.Record.route) },
                )
            }
            composable(Screen.Record.route) {
                RecordScreen(onBack = { navController.popBackStack() })
            }
            composable(Screen.Adventures.route) {
                val vm = hiltViewModel<TripsViewModel>(activityOwner)
                // The ViewModel now outlives the tab, so its init no longer runs
                // per visit — refresh explicitly instead. This composable IS
                // recreated each visit, so LaunchedEffect(Unit) is once per
                // visit. It repaints in place without a spinner (the loaders
                // only show one when there is nothing cached to show), so the
                // switch stays instant and the data still catches up.
                LaunchedEffect(Unit) { vm.loadTrips() }
                TripsTabScreen(
                    trips = {
                        GroupsScreen(
                            tripsViewModel = vm,
                            onTripClick = { id -> navController.navigate("trips/$id") },
                        )
                    },
                    journal = {
                        val jvm = hiltViewModel<JournalsViewModel>(activityOwner)
                        LaunchedEffect(Unit) { jvm.refresh() }
                        JournalsScreen(
                            viewModel = jvm,
                            onOpenMap = { dateStr ->
                                mapViewModel.navigateToDate(dateStr)
                                navController.navigate(Screen.Map.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                        )
                    },
                )
            }
            composable(
                route = Screen.TripDetail.route,
                arguments = listOf(navArgument("tripId") { type = NavType.IntType })
            ) { back ->
                TripDetailScreen(tripId = back.arguments!!.getInt("tripId"), onBack = { navController.popBackStack() })
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
            composable(Screen.Data.route) {
                val toMapDate = { dateStr: String ->
                    mapViewModel.navigateToDate(dateStr)
                    navController.navigate(Screen.Map.route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
                // The app has no distance-unit setting of its own, so follow the
                // phone's region for the flight figures.
                val useMiles = java.util.Locale.getDefault().country in setOf("US", "LR", "MM")
                DataHubScreen { page ->
                    when (page) {
                        DataPage.STATS -> {
                            val vm = hiltViewModel<StatsViewModel>(activityOwner)
                            LaunchedEffect(Unit) { vm.load() }
                            StatsScreen(viewModel = vm, onNavigateToMap = toMapDate)
                        }
                        DataPage.FLIGHTS -> FlightsScreen(
                            viewModel = flightsViewModel,
                            useMiles = useMiles,
                            onScan = { navController.navigate("flights/scan") },
                            onAdd = { flightsViewModel.startNew(); navController.navigate(Screen.FlightEdit.route) },
                            onOpen = { id -> flightsViewModel.startEdit(id); navController.navigate(Screen.FlightEdit.route) },
                        )
                        // Back-stack scoped on purpose: Health's ViewModel is not a
                        // one-shot load (see the Settings comment below).
                        DataPage.HEALTH -> HealthScreen()
                        DataPage.LOCATIONS -> LocationTableScreen()
                    }
                }
            }
            composable(
                route = Screen.FlightScan.route,
                arguments = listOf(navArgument("uri") { type = NavType.StringType; nullable = true; defaultValue = null }),
            ) { back ->
                val uri = back.arguments?.getString("uri")?.let(android.net.Uri::parse)
                ScanPassScreen(
                    initialUri = uri,
                    onBack = { navController.popBackStack() },
                    onFound = { pass, leg, photo ->
                        flightsViewModel.startFromPass(pass, leg, photo)
                        navController.navigate(Screen.FlightEdit.route) {
                            popUpTo(Screen.FlightScan.route) { inclusive = true }
                        }
                    },
                )
            }
            composable(Screen.FlightEdit.route) {
                FlightEditScreen(
                    viewModel = flightsViewModel,
                    onDone = { navController.popBackStack() },
                )
            }
            composable(Screen.TripBlog.route) {
                // Activity-scoped like the other tabs: one-shot loads only, so it
                // is safe to outlive the tab (see activityOwner above). The
                // composer route shares the same instance for its draft.
                val vm = hiltViewModel<TripBlogViewModel>(activityOwner)
                LaunchedEffect(Unit) { vm.load() }
                TripBlogScreen(viewModel = vm, onCompose = { navController.navigate(Screen.TripCompose.route) })
            }
            composable(Screen.TripCompose.route) {
                val vm = hiltViewModel<TripBlogViewModel>(activityOwner)
                TripComposeScreen(viewModel = vm, onDone = { navController.popBackStack(); vm.reload() })
            }
            composable(Screen.Tracker.route) {
                // Back-stack scoped: the screen opens a BLE session for as long
                // as it is visible, and nothing about it is worth keeping.
                TrackerScreen()
            }
            composable(Screen.Settings.route) {
                // DELIBERATELY NOT activity-scoped, unlike the three tabs above.
                // SettingsViewModel is the only one of them with *continuous*
                // collectors, and two of those run against exactly the
                // infrastructure tracking depends on: a Room Flow on the tracking
                // database (pointDao().unsyncedCountFlow(), re-queried on every
                // fix the service inserts) and a WorkManager getWorkInfosByTagFlow,
                // on top of eighteen file-backed DataStore flows.
                //
                // Scoped to the Activity those ran for the whole time the app was
                // alive -- including a whole drive with the screen off, since
                // backgrounding does not destroy the Activity -- in the same
                // process as LocationTrackingService (the manifest declares no
                // android:process), contending on the same SQLite database
                // savePoint() writes into. That starved the capture loop: fix
                // gaps stretched from the configured 10s towards the 45s
                // MIN_ALARM_FLOOR_MS x CATCHUP_MULTIPLIER backstop.
                //
                // Tied to the back stack entry these live only while the tab does,
                // exactly as before. The tab loses nothing by it: its state is
                // DataStore-backed toggles that repaint instantly anyway, which is
                // why this was the least valuable of the four hoists.
                SettingsScreen(
                    viewModel = hiltViewModel(),
                    onLoggedOut = {
                        // Recreate rather than navigate. The tab ViewModels are
                        // scoped to the Activity (see activityOwner above), so
                        // merely routing to Login would leave the previous
                        // account's trips/journals/stats sitting in memory for
                        // whoever signs in next. Recreation drops that store
                        // wholesale, and lands on Login by itself since
                        // logout() has already cleared prefs and isLoggedIn is
                        // false. splashDone is rememberSaveable, so the splash
                        // does not replay.
                        val activity = context.findActivity()
                        if (activity != null) activity.recreate()
                        else navController.navigate(Screen.Login.route) { popUpTo(0) { inclusive = true } }
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

/** Walk the ContextWrapper chain to the hosting Activity, if there is one. */
private fun android.content.Context.findActivity(): android.app.Activity? {
    var ctx: android.content.Context? = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is android.app.Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
