package io.github.jgfyyvzss.gliderpilotlog.ui

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AirplanemodeActive
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.jgfyyvzss.gliderpilotlog.R
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.github.jgfyyvzss.gliderpilotlog.ui.airfields.AirfieldEditScreen
import io.github.jgfyyvzss.gliderpilotlog.ui.airfields.AirfieldListScreen
import io.github.jgfyyvzss.gliderpilotlog.ui.flights.FlightEditScreen
import io.github.jgfyyvzss.gliderpilotlog.ui.flights.FlightListScreen
import io.github.jgfyyvzss.gliderpilotlog.ui.gliders.GliderEditScreen
import io.github.jgfyyvzss.gliderpilotlog.ui.gliders.GliderListScreen
import io.github.jgfyyvzss.gliderpilotlog.ui.home.HomeScreen
import io.github.jgfyyvzss.gliderpilotlog.ui.igc.IgcImportScreen
import io.github.jgfyyvzss.gliderpilotlog.ui.igc.rememberIgcPicker
import io.github.jgfyyvzss.gliderpilotlog.ui.lxnav.LoggerScreen
import io.github.jgfyyvzss.gliderpilotlog.ui.settings.ClubsScreen
import io.github.jgfyyvzss.gliderpilotlog.ui.settings.FirstRunScreen
import io.github.jgfyyvzss.gliderpilotlog.ui.settings.LaunchTypesScreen
import io.github.jgfyyvzss.gliderpilotlog.ui.settings.OpeningBalancesScreen
import io.github.jgfyyvzss.gliderpilotlog.ui.settings.SettingsScreen
import io.github.jgfyyvzss.gliderpilotlog.ui.settings.SpreadsheetsScreen
import io.github.jgfyyvzss.gliderpilotlog.ui.totals.TotalsScreen
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable

@Serializable object HomeRoute
@Serializable object FlightsRoute
@Serializable object TotalsRoute
@Serializable object OpeningBalancesRoute
@Serializable object SpreadsheetsRoute
@Serializable object AirfieldsRoute
@Serializable object GlidersRoute
@Serializable object SettingsRoute
@Serializable object ClubsRoute
@Serializable object LaunchTypesRoute
@Serializable object IgcImportRoute
@Serializable object LoggerRoute
/** [igcSegment] is the index of the IGC segment that pre-fills a new flight, or -1. */
@Serializable data class FlightEditRoute(val id: String? = null, val igcSegment: Int = -1)
@Serializable data class AirfieldEditRoute(val id: String? = null)
@Serializable data class GliderEditRoute(val id: String? = null)

private data class TopLevel(val route: Any, @StringRes val label: Int, val icon: ImageVector)

private val topLevel = listOf(
    TopLevel(HomeRoute, R.string.nav_home, Icons.Default.Home),
    TopLevel(FlightsRoute, R.string.nav_flights, Icons.AutoMirrored.Filled.List),
    TopLevel(TotalsRoute, R.string.nav_totals, Icons.Default.TableChart),
    TopLevel(AirfieldsRoute, R.string.nav_airfields, Icons.Default.Place),
    TopLevel(GlidersRoute, R.string.nav_gliders, Icons.Default.AirplanemodeActive),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoarlogsRoot() {
    // An empty logbook opens on Start fresh or Restore from backup the first time.
    val app = appContainer()
    var firstRun by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) { firstRun = !app.settings.settings.first().firstRunDone && app.backup.isEmpty() }
    when (firstRun) {
        null -> return
        true -> { FirstRunScreen(onDone = { firstRun = false }); return }
        false -> MainScreens()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreens() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val destination = entry?.destination
    val current = topLevel.firstOrNull { t -> destination?.hasRoute(t.route::class) == true }
    // A file opened from the picker or shared by another app starts a fresh import screen.
    val app = appContainer()
    LaunchedEffect(Unit) {
        app.igcImport.openRequests.collect {
            nav.navigate(IgcImportRoute) { popUpTo<IgcImportRoute> { inclusive = true } }
        }
    }
    val title = current?.label ?: when {
        destination?.hasRoute(SettingsRoute::class) == true -> R.string.title_settings
        destination?.hasRoute(ClubsRoute::class) == true -> R.string.title_clubs
        destination?.hasRoute(LaunchTypesRoute::class) == true -> R.string.title_launch_types
        destination?.hasRoute(OpeningBalancesRoute::class) == true -> R.string.title_opening_balances
        destination?.hasRoute(SpreadsheetsRoute::class) == true -> R.string.title_spreadsheets
        destination?.hasRoute(IgcImportRoute::class) == true -> R.string.home_from_igc
        destination?.hasRoute(LoggerRoute::class) == true -> R.string.home_from_logger
        destination?.hasRoute(FlightEditRoute::class) == true ->
            if (entry?.toRoute<FlightEditRoute>()?.id == null) R.string.title_new_flight else R.string.title_flight
        destination?.hasRoute(AirfieldEditRoute::class) == true ->
            if (entry?.toRoute<AirfieldEditRoute>()?.id == null) R.string.title_new_airfield else R.string.title_airfield
        destination?.hasRoute(GliderEditRoute::class) == true ->
            if (entry?.toRoute<GliderEditRoute>()?.id == null) R.string.title_new_glider else R.string.title_glider
        else -> null
    }

    val pickIgc = rememberIgcPicker()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (current == topLevel[0]) stringResource(R.string.app_name) else title?.let { stringResource(it) } ?: "") },
                navigationIcon = {
                    if (current == null) {
                        IconButton(onClick = { nav.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                        }
                    }
                },
                actions = {
                    if (current != null) {
                        IconButton(onClick = { nav.navigate(SettingsRoute) }) {
                            Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.title_settings))
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (current != null) {
                NavigationBar {
                    topLevel.forEach { t ->
                        NavigationBarItem(
                            selected = t == current,
                            onClick = { nav.navigateTopLevel(t.route) },

                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(stringResource(t.label)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = HomeRoute, modifier = Modifier.padding(padding).consumeWindowInsets(padding)) {
            composable<HomeRoute> {
                HomeScreen(
                    onNewFlight = { nav.navigate(FlightEditRoute()) },
                    onFromIgc = pickIgc,
                    onFromLogger = { nav.navigate(LoggerRoute) },
                    onTotals = { nav.navigateTopLevel(TotalsRoute) },
                    onBackup = { nav.navigate(SettingsRoute) },
                )
            }
            composable<TotalsRoute> { TotalsScreen() }
            composable<FlightsRoute> {
                FlightListScreen(onOpen = { nav.navigate(FlightEditRoute(it)) }, onNew = { nav.navigate(FlightEditRoute()) })
            }
            composable<FlightEditRoute> {
                val route = it.toRoute<FlightEditRoute>()
                FlightEditScreen(id = route.id, igcSegment = route.igcSegment.takeIf { s -> s >= 0 }, onDone = { nav.popBackStack() })
            }
            composable<LoggerRoute> { LoggerScreen() }
            composable<IgcImportRoute> {
                IgcImportScreen(onImport = { i -> nav.navigate(FlightEditRoute(igcSegment = i)) }, onFinish = { nav.popBackStack() })
            }
            composable<AirfieldsRoute> {
                AirfieldListScreen(onOpen = { nav.navigate(AirfieldEditRoute(it)) }, onNew = { nav.navigate(AirfieldEditRoute()) })
            }
            composable<AirfieldEditRoute> { AirfieldEditScreen(id = it.toRoute<AirfieldEditRoute>().id, onDone = { nav.popBackStack() }) }
            composable<GlidersRoute> {
                GliderListScreen(onOpen = { nav.navigate(GliderEditRoute(it)) }, onNew = { nav.navigate(GliderEditRoute()) })
            }
            composable<GliderEditRoute> { GliderEditScreen(id = it.toRoute<GliderEditRoute>().id, onDone = { nav.popBackStack() }) }
            composable<SettingsRoute> {
                SettingsScreen(
                    onClubs = { nav.navigate(ClubsRoute) },
                    onLaunchTypes = { nav.navigate(LaunchTypesRoute) },
                    onOpeningBalances = { nav.navigate(OpeningBalancesRoute) },
                    onSpreadsheets = { nav.navigate(SpreadsheetsRoute) },
                )
            }
            composable<ClubsRoute> { ClubsScreen() }
            composable<LaunchTypesRoute> { LaunchTypesScreen() }
            composable<OpeningBalancesRoute> { OpeningBalancesScreen() }
            composable<SpreadsheetsRoute> { SpreadsheetsScreen() }
        }
    }
}

/** Switches tabs, keeping each tab's own back stack. */
private fun NavHostController.navigateTopLevel(route: Any) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
