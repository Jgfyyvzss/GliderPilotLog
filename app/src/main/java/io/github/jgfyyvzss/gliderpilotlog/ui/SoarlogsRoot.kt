package io.github.jgfyyvzss.gliderpilotlog.ui

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
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
@Serializable data class FlightEditRoute(val id: String? = null)
@Serializable data class AirfieldEditRoute(val id: String? = null)
@Serializable data class GliderEditRoute(val id: String? = null)

private data class TopLevel(val route: Any, val label: String, val icon: ImageVector)

private val topLevel = listOf(
    TopLevel(HomeRoute, "Home", Icons.Default.Home),
    TopLevel(FlightsRoute, "Flights", Icons.AutoMirrored.Filled.List),
    TopLevel(TotalsRoute, "Totals", Icons.Default.TableChart),
    TopLevel(AirfieldsRoute, "Airfields", Icons.Default.Place),
    TopLevel(GlidersRoute, "Gliders", Icons.Default.AirplanemodeActive),
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
    val title = current?.label ?: when {
        destination?.hasRoute(SettingsRoute::class) == true -> "Settings"
        destination?.hasRoute(ClubsRoute::class) == true -> "Clubs"
        destination?.hasRoute(LaunchTypesRoute::class) == true -> "Launch types"
        destination?.hasRoute(OpeningBalancesRoute::class) == true -> "Opening balances"
        destination?.hasRoute(SpreadsheetsRoute::class) == true -> "Spreadsheets (CSV)"
        destination?.hasRoute(FlightEditRoute::class) == true ->
            if (entry?.toRoute<FlightEditRoute>()?.id == null) "New flight" else "Flight"
        destination?.hasRoute(AirfieldEditRoute::class) == true ->
            if (entry?.toRoute<AirfieldEditRoute>()?.id == null) "New airfield" else "Airfield"
        destination?.hasRoute(GliderEditRoute::class) == true ->
            if (entry?.toRoute<GliderEditRoute>()?.id == null) "New glider" else "Glider"
        else -> ""
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (current == topLevel[0]) "soarlogs" else title) },
                navigationIcon = {
                    if (current == null) {
                        IconButton(onClick = { nav.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (current != null) {
                        IconButton(onClick = { nav.navigate(SettingsRoute) }) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
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
                            label = { Text(t.label) },
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
                    onTotals = { nav.navigateTopLevel(TotalsRoute) },
                    onBackup = { nav.navigate(SettingsRoute) },
                )
            }
            composable<TotalsRoute> { TotalsScreen() }
            composable<FlightsRoute> {
                FlightListScreen(onOpen = { nav.navigate(FlightEditRoute(it)) }, onNew = { nav.navigate(FlightEditRoute()) })
            }
            composable<FlightEditRoute> { FlightEditScreen(id = it.toRoute<FlightEditRoute>().id, onDone = { nav.popBackStack() }) }
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
