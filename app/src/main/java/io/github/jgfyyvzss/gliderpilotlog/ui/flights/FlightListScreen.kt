package io.github.jgfyyvzss.gliderpilotlog.ui.flights

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import io.github.jgfyyvzss.gliderpilotlog.core.Durations
import io.github.jgfyyvzss.gliderpilotlog.core.Role
import io.github.jgfyyvzss.gliderpilotlog.data.FlightListRow
import io.github.jgfyyvzss.gliderpilotlog.data.LogbookRepository
import io.github.jgfyyvzss.gliderpilotlog.ui.isoDateShort
import io.github.jgfyyvzss.gliderpilotlog.ui.vmFactory

class FlightListViewModel(repo: LogbookRepository) : ViewModel() {
    val flights = Pager(PagingConfig(pageSize = 60, enablePlaceholders = false)) { repo.pagedFlights() }
        .flow.cachedIn(viewModelScope)
}

/** Newest first by date then log number. Filters and the logbook calculator come in later steps. */
@Composable
fun FlightListScreen(onOpen: (String) -> Unit, onNew: () -> Unit) {
    val vm: FlightListViewModel = viewModel(factory = vmFactory { FlightListViewModel(it.repository) })
    val items = vm.flights.collectAsLazyPagingItems()
    Box(Modifier.fillMaxSize()) {
        if (items.itemCount == 0) Text("No flights yet. Tap + to log one.", Modifier.padding(16.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(items.itemCount, key = items.itemKey { it.id }) { index ->
                val row = items[index] ?: return@items
                ListItem(
                    headlineContent = { Text(row.firstLine()) },
                    supportingContent = { Text(row.secondLine()) },
                    modifier = Modifier.clickable { onOpen(row.id) },
                )
                HorizontalDivider()
            }
        }
        FloatingActionButton(onClick = onNew, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            Icon(Icons.Default.Add, contentDescription = "New flight")
        }
    }
}

/** For example `340 · 17/10/26 · FQM Hornet`. */
fun FlightListRow.firstLine(): String =
    listOf(logNo.toString(), isoDateShort(date), "$registration $gliderType".trim()).joinToString(" · ")

/** For example `YMBT · Winch · PIC · 4:31`. */
fun FlightListRow.secondLine(): String {
    val roleLabel = runCatching { Role.valueOf(role).label }.getOrDefault(role)
    return listOf(airfieldName, launchTypeName, roleLabel, Durations.formatHm(durationMinutes)).joinToString(" · ")
}
