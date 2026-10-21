package io.github.jgfyyvzss.gliderpilotlog.ui.flights

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.paging.insertSeparators
import androidx.paging.map
import io.github.jgfyyvzss.gliderpilotlog.data.GliderEntity
import io.github.jgfyyvzss.gliderpilotlog.ui.gliders.pickerLabel
import io.github.jgfyyvzss.gliderpilotlog.ui.logbook.LogbookCalculatorSheet
import java.time.LocalDate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.jgfyyvzss.gliderpilotlog.R
import io.github.jgfyyvzss.gliderpilotlog.ui.label
import io.github.jgfyyvzss.gliderpilotlog.ui.launchTypeName
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import io.github.jgfyyvzss.gliderpilotlog.core.HoursDisplay
import io.github.jgfyyvzss.gliderpilotlog.core.HoursFormat
import io.github.jgfyyvzss.gliderpilotlog.core.Role
import io.github.jgfyyvzss.gliderpilotlog.data.FlightListRow
import io.github.jgfyyvzss.gliderpilotlog.data.FilterStore
import io.github.jgfyyvzss.gliderpilotlog.data.LogbookRepository
import io.github.jgfyyvzss.gliderpilotlog.data.Settings
import io.github.jgfyyvzss.gliderpilotlog.ui.appContainer
import io.github.jgfyyvzss.gliderpilotlog.ui.isoDateShort
import io.github.jgfyyvzss.gliderpilotlog.ui.vmFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest

/** A list entry: a day header, or a flight. */
sealed interface FlightListItem {
    data class Day(val date: String) : FlightListItem
    data class Flight(val row: FlightListRow) : FlightListItem
}

class FlightListViewModel(private val repo: LogbookRepository, filterStore: FilterStore) : ViewModel() {
    val filter = filterStore.filter

    /** Flights with a header before the first flight of each day. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val flights = filterStore.filter.flatMapLatest { f ->
        Pager(PagingConfig(pageSize = 60, enablePlaceholders = false)) { repo.pagedFlights(f) }.flow.map { page ->
            page.map<FlightListRow, FlightListItem> { FlightListItem.Flight(it) }.insertSeparators { before, after ->
                val a = (after as? FlightListItem.Flight)?.row ?: return@insertSeparators null
                val b = (before as? FlightListItem.Flight)?.row
                if (b == null || b.date != a.date) FlightListItem.Day(a.date) else null
            }
        }
    }.cachedIn(viewModelScope)

    /** The day and glider the calculator is open for. */
    var calculator by mutableStateOf<Pair<String, LocalDate>?>(null)
    /** Gliders to choose from when more than one flew on the chosen day. */
    var chooseGlider by mutableStateOf<Pair<LocalDate, List<GliderEntity>>?>(null)

    /** Opens the calculator for a day, straight away for one glider or after a choice for several. */
    fun openCalculator(date: LocalDate) = viewModelScope.launch {
        val gliders = repo.glidersOnDay(date)
        when (gliders.size) {
            0 -> Unit
            1 -> calculator = gliders.single().id to date
            else -> chooseGlider = date to gliders
        }
    }
}

/** Newest first by date then log number, filtered by the shared filter, with an aircraft logbook button per day. */
@Composable
fun FlightListScreen(onOpen: (String) -> Unit, onNew: () -> Unit) {
    val vm: FlightListViewModel = viewModel(factory = vmFactory { FlightListViewModel(it.repository, it.filter) })
    val items = vm.flights.collectAsLazyPagingItems()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val settings by appContainer().settings.settings.collectAsStateWithLifecycle(Settings())
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            FilterBar()
            if (items.itemCount == 0) {
                Text(stringResource(if (filter.isActive) R.string.flights_none_match else R.string.flights_none_yet), Modifier.padding(16.dp))
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(
                    items.itemCount,
                    key = items.itemKey { if (it is FlightListItem.Day) "day-${it.date}" else (it as FlightListItem.Flight).row.id },
                ) { index ->
                    when (val item = items[index]) {
                        is FlightListItem.Day -> DayHeader(item.date) { vm.openCalculator(LocalDate.parse(item.date)) }
                        is FlightListItem.Flight -> {
                            val row = item.row
                            ListItem(
                                headlineContent = { Text(row.firstLine()) },
                                supportingContent = { Text(row.secondLine(settings.hoursDisplay)) },
                                modifier = Modifier.clickable { onOpen(row.id) },
                            )
                            HorizontalDivider()
                        }
                        null -> Unit
                    }
                }
            }
        }
        FloatingActionButton(onClick = onNew, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.home_new_flight))
        }
    }

    vm.chooseGlider?.let { (date, gliders) ->
        AlertDialog(
            onDismissRequest = { vm.chooseGlider = null },
            title = { Text(stringResource(R.string.flights_which_glider)) },
            text = {
                Column {
                    gliders.forEach { g ->
                        ListItem(
                            headlineContent = { Text(g.pickerLabel()) },
                            modifier = Modifier.clickable { vm.chooseGlider = null; vm.calculator = g.id to date },
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { vm.chooseGlider = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
    vm.calculator?.let { (gliderId, date) -> LogbookCalculatorSheet(gliderId, date) { vm.calculator = null } }
}

@Composable
private fun DayHeader(isoDate: String, onLogbook: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(isoDateShort(isoDate), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        TextButton(onClick = onLogbook) { Text(stringResource(R.string.flights_aircraft_logbook)) }
    }
}

/** For example `340 · 17/10/26 · FQM Hornet`. */
fun FlightListRow.firstLine(): String =
    listOf(logNo.toString(), isoDateShort(date), "$registration $gliderType".trim()).joinToString(" · ")

/** For example `YMBT · Winch · PIC · 4:31`, with role and seeded launch type in the pilot's language. */
@Composable
fun FlightListRow.secondLine(display: HoursDisplay = HoursDisplay.HM): String {
    val roleLabel = Role.entries.firstOrNull { it.name == role }?.label() ?: role
    return listOf(airfieldName, launchTypeName(launchTypeId, launchTypeName), roleLabel, HoursFormat.format(durationMinutes, display))
        .joinToString(" · ")
}
