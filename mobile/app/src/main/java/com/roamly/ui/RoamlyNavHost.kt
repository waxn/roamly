package com.roamly.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DirectionsRun
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.roamly.flights.IncomingShare
import com.roamly.notes.QuickNoteLaunch
import com.roamly.ui.ask.AskViewModel
import com.roamly.ui.auth.AuthViewModel
import com.roamly.ui.auth.LoginScreen
import com.roamly.ui.family.FamilyScreen
import com.roamly.ui.flights.FlightEditScreen
import com.roamly.ui.flights.FlightsScreen
import com.roamly.ui.flights.FlightsViewModel
import com.roamly.ui.flights.ScanPassScreen
import com.roamly.ui.groups.GroupsScreen
import com.roamly.ui.health.HealthScreen
import com.roamly.ui.journals.JournalsScreen
import com.roamly.ui.journals.JournalsViewModel
import com.roamly.ui.kit.LocalScreenBack
import com.roamly.ui.kit.ProvideDockPadding
import com.roamly.ui.map.MapScreen
import com.roamly.ui.map.MapViewModel
import com.roamly.ui.notes.QuickNotesViewModel
import com.roamly.ui.record.ActivityDetailScreen
import com.roamly.ui.record.RecordLaunch
import com.roamly.ui.record.RecordScreen
import com.roamly.ui.search.SearchTabScreen
import com.roamly.ui.settings.DiagnosticsScreen
import com.roamly.ui.settings.LocationTableScreen
import com.roamly.ui.settings.SettingsScreen
import com.roamly.ui.settings.SettingsViewModel
import com.roamly.ui.stats.StatsScreen
import com.roamly.ui.stats.StatsViewModel
import com.roamly.ui.theme.Atlas
import com.roamly.ui.tracker.TrackerScreen
import com.roamly.ui.tripblog.TripBlogScreen
import com.roamly.ui.tripblog.TripBlogViewModel
import com.roamly.ui.tripblog.TripComposeScreen
import com.roamly.ui.trips.TripDetailScreen
import com.roamly.ui.trips.TripsTabScreen
import com.roamly.ui.trips.TripsViewModel
import com.roamly.ui.update.UpdateBanner
import com.roamly.ui.update.UpdateViewModel
import com.roamly.ui.you.YouScreen

/** The four dock tabs. Everything else is a pushed route over them. */
enum class Tab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Map("map", "Map", Icons.Rounded.Map),
    Trips("trips", "Write", Icons.Rounded.EditNote),
    Record("record", "Activity", Icons.Rounded.DirectionsRun),
    You("you", "You", Icons.Rounded.Person),
}

/** Pushed routes (no dock). */
object Routes {
    const val LOGIN = "login"
    const val TRIP_DETAIL = "trips/{tripId}"
    // A route rather than a tab: system back just works, and recording carries
    // on in the foreground service either way (the notification says so).
    const val ASK = "ask"
    const val ACTIVITY = "activity/{id}"
    const val TRACKER = "tracker"
    const val FLIGHT_SCAN = "flights/scan?uri={uri}"
    const val FLIGHT_EDIT = "flights/edit"
    const val TRIP_BLOG = "tripblog"
    const val TRIP_COMPOSE = "tripblog/compose"
    const val STATS = "stats"
    const val FLIGHTS = "flights"
    const val HEALTH = "health"
    const val LOCATIONS = "locations"
    const val SETTINGS = "settings"
    const val DIAGNOSTICS = "diagnostics"
    const val FAMILY = "family"
}

private val tabRoutes = Tab.entries.map { it.route }.toSet()

/** Pushed pages that borrow a root page's header and so need its back button. */
private val backHeaderRoutes = setOf(Routes.ASK)

private fun NavBackStackEntry.isTab() = destination.route in tabRoutes

/** Switch tabs the standard way: one copy of each, state saved and restored. */
private fun NavController.toTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@Composable
fun RoamlyNavHost() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val authViewModel: AuthViewModel = hiltViewModel()
    val mapViewModel: MapViewModel = hiltViewModel()
    val updateViewModel: UpdateViewModel = hiltViewModel()
    val askViewModel: AskViewModel = hiltViewModel()
    // Activity-scoped: the scanner hands its draft to the editor through it,
    // across two routes. No collectors in its init.
    val flightsViewModel: FlightsViewModel = hiltViewModel()

    // The Activity's ViewModelStoreOwner, captured out here where
    // LocalViewModelStoreOwner still resolves to the Activity rather than to a
    // NavBackStackEntry. Tabs pass it to hiltViewModel() so their ViewModels
    // survive a tab switch (saveState=true clears a back-stack entry's store).
    // Resolved lazily per destination, never here: these inits fire network
    // calls at once, and during the splash that would hit the placeholder base
    // URL before login resolves. See docs/claude/mobile-app.md.
    val activityOwner = checkNotNull(LocalViewModelStoreOwner.current) {
        "No ViewModelStoreOwner — RoamlyNavHost must be hosted by the Activity"
    }
    // Safe to build eagerly: its init loads nothing.
    val quickNotesViewModel = hiltViewModel<QuickNotesViewModel>(activityOwner)
    val isLoggedIn by authViewModel.isLoggedIn.collectAsState()
    val simpleMode by authViewModel.simpleModeEnabled.collectAsState()
    // Simple Mode: a non-technical family member gets the map and You and nothing else.
    val tabs = if (simpleMode) listOf(Tab.Map, Tab.You) else Tab.entries.toList()
    val trackerPaired by authViewModel.trackerPaired.collectAsState()
    val featuredTrip by authViewModel.featuredTripId.collectAsState()
    val featuredTripName by authViewModel.featuredTripName.collectAsState()
    val recording by authViewModel.recordingActive.collectAsState()
    val username by authViewModel.username.collectAsState()

    // Ask for notification permission once after the first sign-in: without it
    // the reminder and Family alerts silently never show.
    val notifLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(isLoggedIn) {
        if (isLoggedIn != true || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return@LaunchedEffect
        val prompts = context.getSharedPreferences("roamly_prompts", android.content.Context.MODE_PRIVATE)
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted && !prompts.getBoolean("asked_notifications", false)) {
            prompts.edit().putBoolean("asked_notifications", true).apply()
            notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(isLoggedIn) {
        if (isLoggedIn == true) {
            // RoamlyApp's start-up arm ran before sign-in on a fresh install.
            quickNotesViewModel.ensureReminderScheduled()
            updateViewModel.checkOnLaunch()
            authViewModel.refreshFeaturedTrip()
            authViewModel.registerPush()
        }
    }

    var splashDone by rememberSaveable { mutableStateOf(false) }
    if (!splashDone || isLoggedIn == null) {
        SplashScreen(onFinished = { splashDone = true })
        return
    }

    val initialRoute = if (isLoggedIn == true) Tab.Map.route else Routes.LOGIN
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // The session went invalid while open (SessionGuard cleared it): back to login.
    LaunchedEffect(isLoggedIn) {
        if (isLoggedIn == false && currentRoute != null && currentRoute != Routes.LOGIN) {
            navController.navigate(Routes.LOGIN) { popUpTo(0) { inclusive = true } }
        }
    }

    // The evening note reminder was tapped: bring the map forward, where
    // MapScreen opens the note sheet and consumes the request.
    val noteLaunch by QuickNoteLaunch.requested.collectAsState()
    LaunchedEffect(noteLaunch, isLoggedIn, currentRoute) {
        if (!noteLaunch || isLoggedIn != true || currentRoute == null) return@LaunchedEffect
        if (currentRoute == Routes.LOGIN || currentRoute == Tab.Map.route) return@LaunchedEffect
        navController.toTab(Tab.Map.route)
    }

    // A boarding pass shared into Roamly opens the scanner on it, once signed in.
    val sharedUri by IncomingShare.uri.collectAsState()
    LaunchedEffect(sharedUri, isLoggedIn, currentRoute) {
        val uri = sharedUri ?: return@LaunchedEffect
        if (isLoggedIn != true || currentRoute == null || currentRoute == Routes.LOGIN) return@LaunchedEffect
        IncomingShare.consume()
        navController.navigate("flights/scan?uri=" + android.net.Uri.encode(uri.toString()))
    }

    val showDock = isLoggedIn == true && currentRoute in tabRoutes && !(recording && currentRoute == Tab.Record.route)
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val dockSpace = DOCK_HEIGHT + DOCK_MARGIN + navBottom
    val toMap = { navController.toTab(Tab.Map.route) }
    val toMapDate = { date: String -> mapViewModel.navigateToDate(date); toMap() }
    val open = { route: String -> navController.navigate(route) }
    val back: () -> Unit = { navController.popBackStack() }
    val updateState by updateViewModel.state.collectAsState()

    Box(Modifier.fillMaxSize().background(Atlas.colors.bg)) {
        ProvideDockPadding(if (showDock) dockSpace else navBottom) {
          CompositionLocalProvider(LocalScreenBack provides if (currentRoute in backHeaderRoutes) back else null) {
            NavHost(
                navController = navController,
                startDestination = initialRoute,
                modifier = Modifier.fillMaxSize(),
                enterTransition = { enter() },
                exitTransition = { exit() },
                popEnterTransition = { popEnter() },
                popExitTransition = { popExit() },
            ) {
                composable(Routes.LOGIN) {
                    LoginScreen(
                        viewModel = authViewModel,
                        onLoggedIn = {
                            navController.navigate(Tab.Map.route) { popUpTo(Routes.LOGIN) { inclusive = true } }
                        },
                    )
                }

                // ── Tabs ─────────────────────────────────────────────────
                composable(Tab.Map.route) {
                    MapScreen(
                        viewModel = mapViewModel,
                        notesViewModel = quickNotesViewModel,
                        recording = recording,
                        simpleMode = simpleMode,
                        onOpen = open,
                    )
                }
                composable(Tab.Trips.route) {
                    val vm = hiltViewModel<TripsViewModel>(activityOwner)
                    // The ViewModel outlives the tab, so refresh per visit (it
                    // repaints in place, no spinner, when it has data cached).
                    LaunchedEffect(Unit) { vm.loadTrips() }
                    TripsTabScreen(
                        featuredBlogName = featuredTripName.takeIf { featuredTrip != null },
                        onOpenBlog = { open(Routes.TRIP_BLOG) },
                        trips = {
                            GroupsScreen(tripsViewModel = vm, onTripClick = { id -> open("trips/$id") })
                        },
                        journal = {
                            val jvm = hiltViewModel<JournalsViewModel>(activityOwner)
                            LaunchedEffect(Unit) { jvm.refresh() }
                            JournalsScreen(viewModel = jvm, onOpenMap = toMapDate)
                        },
                    )
                }
                composable(Routes.ASK) {
                    SearchTabScreen(
                        askViewModel = askViewModel,
                        onOpenMapDate = toMapDate,
                        onFocusMap = { lat, lng -> mapViewModel.focusOn(lat, lng, zoom = 16.0); toMap() },
                    )
                }
                composable(Tab.You.route) {
                    // Back-stack scoped on purpose: SettingsViewModel has continuous
                    // collectors on the tracking database and WorkManager, which must
                    // not outlive the screen (they starved the capture loop when they
                    // did — see docs/claude/mobile-app.md).
                    YouScreen(
                        viewModel = hiltViewModel<SettingsViewModel>(),
                        username = username,
                        simpleMode = simpleMode,
                        trackerPaired = trackerPaired,
                        featuredBlogName = featuredTripName.takeIf { featuredTrip != null },
                        updateState = updateState,
                        onUpdate = updateViewModel::downloadAndInstall,
                        onOpen = open,
                        onLoggedOut = {
                            // Recreate rather than navigate, so the Activity-scoped tab
                            // ViewModels holding the old account's data go with it.
                            val activity = context.findActivity()
                            if (activity != null) activity.recreate()
                            else navController.navigate(Routes.LOGIN) { popUpTo(0) { inclusive = true } }
                        },
                    )
                }

                // ── Pushed ───────────────────────────────────────────────
                composable(Tab.Record.route) {
                    RecordScreen(onBack = toMap, onOpenActivity = { id -> open("activity/$id") })
                }
                composable(
                    route = Routes.ACTIVITY,
                    arguments = listOf(navArgument("id") { type = NavType.IntType }),
                ) { entry -> ActivityDetailScreen(id = entry.arguments!!.getInt("id"), onBack = back) }
                composable(
                    route = Routes.TRIP_DETAIL,
                    arguments = listOf(navArgument("tripId") { type = NavType.IntType }),
                ) { entry ->
                    TripDetailScreen(tripId = entry.arguments!!.getInt("tripId"), onBack = back)
                }
                composable(Routes.STATS) {
                    val vm = hiltViewModel<StatsViewModel>(activityOwner)
                    LaunchedEffect(Unit) { vm.load() }
                    StatsScreen(viewModel = vm, onNavigateToMap = toMapDate, onBack = back)
                }
                composable(Routes.FLIGHTS) {
                    // No unit setting of its own: follow the phone's region.
                    val useMiles = java.util.Locale.getDefault().country in setOf("US", "LR", "MM")
                    FlightsScreen(
                        viewModel = flightsViewModel,
                        useMiles = useMiles,
                        onBack = back,
                        onScan = { open("flights/scan") },
                        onAdd = { flightsViewModel.startNew(); open(Routes.FLIGHT_EDIT) },
                        onOpen = { id -> flightsViewModel.startEdit(id); open(Routes.FLIGHT_EDIT) },
                    )
                }
                // Back-stack scoped: Health's ViewModel is not a one-shot load.
                composable(Routes.HEALTH) { HealthScreen(onBack = back) }
                composable(Routes.LOCATIONS) { LocationTableScreen(onBack = back) }
                composable(
                    route = Routes.FLIGHT_SCAN,
                    arguments = listOf(navArgument("uri") { type = NavType.StringType; nullable = true; defaultValue = null }),
                ) { entry ->
                    val uri = entry.arguments?.getString("uri")?.let(android.net.Uri::parse)
                    ScanPassScreen(
                        initialUri = uri,
                        onBack = back,
                        onFound = { pass, leg, photo ->
                            flightsViewModel.startFromPass(pass, leg, photo)
                            navController.navigate(Routes.FLIGHT_EDIT) { popUpTo(Routes.FLIGHT_SCAN) { inclusive = true } }
                        },
                    )
                }
                composable(Routes.FLIGHT_EDIT) { FlightEditScreen(viewModel = flightsViewModel, onDone = back) }
                composable(Routes.TRIP_BLOG) {
                    // One-shot loads only, so safe to share with the composer.
                    val vm = hiltViewModel<TripBlogViewModel>(activityOwner)
                    LaunchedEffect(Unit) { vm.load() }
                    TripBlogScreen(viewModel = vm, onBack = back, onCompose = { open(Routes.TRIP_COMPOSE) })
                }
                composable(Routes.TRIP_COMPOSE) {
                    val vm = hiltViewModel<TripBlogViewModel>(activityOwner)
                    TripComposeScreen(viewModel = vm, onDone = { navController.popBackStack(); vm.reload() })
                }
                // Back-stack scoped: holds a BLE session open while visible.
                composable(Routes.TRACKER) { TrackerScreen(onBack = back) }
                composable(Routes.FAMILY) { FamilyScreen(onBack = back) }
                // Back-stack scoped, like You — see the note there.
                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        viewModel = hiltViewModel(),
                        updateViewModel = updateViewModel,
                        onBack = back,
                        onOpenDiagnostics = { open(Routes.DIAGNOSTICS) },
                    )
                }
                composable(Routes.DIAGNOSTICS) {
                    DiagnosticsScreen(viewModel = hiltViewModel(), onBack = back)
                }
            }
          }
        }

        // Tabs other than the map scroll under the status bar: give it a ground
        // so content never runs into the clock.
        if (showDock && currentRoute != Tab.Map.route || currentRoute == Routes.ASK) {
            Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(Atlas.colors.bg.copy(alpha = 0.94f)))
        }

        if (showDock) {
            UpdateBanner(
                state = updateState,
                onUpdate = updateViewModel::downloadAndInstall,
                onDismiss = updateViewModel::dismissBanner,
                modifier = Modifier.align(Alignment.BottomCenter),
                bottomOffset = dockSpace,
            )
        }
        FloatingNav(
            visible = showDock,
            tabs = tabs,
            selected = Tab.entries.firstOrNull { it.route == currentRoute },
            recording = recording,
            onSelect = { tab -> if (tab.route != currentRoute) navController.toTab(tab.route) },
            // Simple Mode has no recording; the centre button only exists with the full dock.
            onRecord = if (simpleMode) null else {
                { RecordLaunch.request(); if (currentRoute != Tab.Record.route) navController.toTab(Tab.Record.route) }
            },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

// ── Transitions ─────────────────────────────────────────────────────────────
// Tab ↔ tab: a quick crossfade with a hint of scale. Into a pushed screen: it
// slides in from the right over the one beneath, which drifts left and dims.

private const val TAB_MS = 150
private const val PUSH_MS = 220

// Pages travel the full width on a plain ease so the back gesture drags the page
// off with the finger and finishes in one move: no shrinking, no stall, no fade.
private val PushEase = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f)

private fun AnimatedContentTransitionScope<NavBackStackEntry>.tabSwap() =
    initialState.isTab() && targetState.isTab()

private fun AnimatedContentTransitionScope<NavBackStackEntry>.enter(): EnterTransition =
    if (tabSwap()) fadeIn(tween(TAB_MS)) + scaleIn(tween(TAB_MS), initialScale = 0.985f)
    else slideInHorizontally(tween(PUSH_MS, easing = PushEase)) { it }

private fun AnimatedContentTransitionScope<NavBackStackEntry>.exit(): ExitTransition =
    if (tabSwap()) fadeOut(tween(TAB_MS / 2))
    else slideOutHorizontally(tween(PUSH_MS, easing = PushEase)) { -it / 5 }

private fun AnimatedContentTransitionScope<NavBackStackEntry>.popEnter(): EnterTransition =
    if (tabSwap()) fadeIn(tween(TAB_MS))
    else slideInHorizontally(tween(PUSH_MS, easing = PushEase)) { -it / 5 }

private fun AnimatedContentTransitionScope<NavBackStackEntry>.popExit(): ExitTransition =
    if (tabSwap()) fadeOut(tween(TAB_MS / 2)) + scaleOut(tween(TAB_MS), targetScale = 0.985f)
    else slideOutHorizontally(tween(PUSH_MS, easing = PushEase)) { it }

/** Walk the ContextWrapper chain to the hosting Activity, if there is one. */
private fun android.content.Context.findActivity(): android.app.Activity? {
    var ctx: android.content.Context? = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is android.app.Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

internal val DOCK_HEIGHT = 68.dp
internal val DOCK_MARGIN = 14.dp
/** Same side inset as the map's controls, so the dock lines up with them. */
internal val DOCK_SIDE = 14.dp
