package io.github.jgfyyvzss.gliderpilotlog.ui.gliders

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.input.KeyboardType
import io.github.jgfyyvzss.gliderpilotlog.core.Durations
import io.github.jgfyyvzss.gliderpilotlog.core.HoursFormat
import io.github.jgfyyvzss.gliderpilotlog.core.LogbookCalculator
import io.github.jgfyyvzss.gliderpilotlog.data.Settings
import io.github.jgfyyvzss.gliderpilotlog.ui.isoDateShort
import io.github.jgfyyvzss.gliderpilotlog.ui.logbook.LogbookCalculatorSheet
import io.github.jgfyyvzss.gliderpilotlog.ui.weglide.WeGlideTypeSection
import io.github.jgfyyvzss.gliderpilotlog.ui.shortText
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.jgfyyvzss.gliderpilotlog.data.GliderEntity
import io.github.jgfyyvzss.gliderpilotlog.data.LogbookRepository
import io.github.jgfyyvzss.gliderpilotlog.data.SaveResult
import io.github.jgfyyvzss.gliderpilotlog.ui.Badge
import io.github.jgfyyvzss.gliderpilotlog.ui.ColonTransformation
import io.github.jgfyyvzss.gliderpilotlog.ui.appContainer
import io.github.jgfyyvzss.gliderpilotlog.ui.vmFactory
import kotlinx.coroutines.launch

@Composable
fun GliderListScreen(onOpen: (String) -> Unit, onNew: () -> Unit) {
    val gliders by appContainer().repository.observeGliders().collectAsStateWithLifecycle(emptyList())
    Box(Modifier.fillMaxSize()) {
        if (gliders.isEmpty()) {
            Text("No gliders yet. Tap + to add one.", Modifier.padding(16.dp))
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(gliders, key = { it.id }) { g ->
                ListItem(
                    headlineContent = { Text(g.registration) },
                    supportingContent = { if (g.type.isNotEmpty()) Text(g.type) },
                    trailingContent = {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (g.incomplete) Badge("Incomplete")
                            if (g.weglideAircraftId == null && !g.archived) Badge("No WeGlide type")
                            if (g.archived) Badge("Archived")
                        }
                    },
                    modifier = Modifier.clickable { onOpen(g.id) },
                )
                HorizontalDivider()
            }
        }
        FloatingActionButton(onClick = onNew, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            Icon(Icons.Default.Add, contentDescription = "Add glider")
        }
    }
}

class GliderEditViewModel(private val repo: LogbookRepository, private val id: String?) : ViewModel() {
    var registration by mutableStateOf("")
    var type by mutableStateOf("")
    var archived by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var loaded by mutableStateOf(id == null)
        private set
    var done by mutableStateOf(false)
        private set

    init {
        if (id != null) viewModelScope.launch {
            repo.glider(id)?.let { registration = it.registration; type = it.type; archived = it.archived }
            loaded = true
        }
    }

    fun save() = viewModelScope.launch {
        when (val r = repo.saveGlider(id, registration, type, archived)) {
            is SaveResult.Saved -> done = true
            is SaveResult.Rejected -> error = r.message
        }
    }
}

@Composable
fun GliderEditScreen(id: String?, onDone: () -> Unit) {
    val vm: GliderEditViewModel = viewModel(factory = vmFactory { GliderEditViewModel(it.repository, id) })
    LaunchedEffect(vm.done) { if (vm.done) onDone() }
    if (!vm.loaded) return
    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = vm.registration,
            onValueChange = { vm.registration = it; vm.error = null },
            label = { Text("Registration") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = vm.type,
            onValueChange = { vm.type = it },
            label = { Text("Type, as the logbook shows it") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (id != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Archived", Modifier.weight(1f))
                Switch(checked = vm.archived, onCheckedChange = { vm.archived = it })
            }
            Text("Archived gliders are hidden from the flight form.", style = MaterialTheme.typography.bodySmall)
        }
        vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = { vm.save() }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
        if (id != null) {
            WeGlideTypeSection(id) { vm.type = it }
            LogbookTotalSection(id)
        }
    }
}

/** The total last written in the aircraft logbook, never a computed airframe total, and the manual calculator. */
@Composable
private fun LogbookTotalSection(gliderId: String) {
    val app = appContainer()
    val glider by remember { app.repository.observeGlider(gliderId) }.collectAsStateWithLifecycle(null)
    val settings by app.settings.settings.collectAsStateWithLifecycle(Settings())
    var calculator by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    val g = glider ?: return

    HorizontalDivider()
    Text("Aircraft logbook", style = MaterialTheme.typography.titleMedium)
    Text(
        g.logbookTotalMinutes?.let { m ->
            "Logbook total as last entered: ${HoursFormat.format(m, settings.hoursDisplay)}" +
                (g.logbookTotalFlights?.let { ", $it flights" } ?: "") +
                (g.logbookTotalDate?.let { ", ${isoDateShort(it)}" } ?: "")
        } ?: "No logbook total recorded.",
    )
    Text(
        "The last total you wrote in the glider's own logbook, kept as the calculator's starting point. Others may fly " +
            "the glider, so check it against the real logbook.",
        style = MaterialTheme.typography.bodySmall,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = { calculator = true }, modifier = Modifier.weight(1f)) { Text("Calculator") }
        OutlinedButton(onClick = { editing = true }, modifier = Modifier.weight(1f)) { Text("Edit total") }
    }

    if (calculator) LogbookCalculatorSheet(gliderId, day = null) { calculator = false }
    if (editing) EditTotalDialog(g, onDismiss = { editing = false })
}

/** Edit or clear the stored logbook total by hand. Hours and date go together; the flight count is optional. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditTotalDialog(g: GliderEntity, onDismiss: () -> Unit) {
    val app = appContainer()
    val scope = rememberCoroutineScope()
    var hours by rememberSaveable { mutableStateOf(g.logbookTotalMinutes?.let(Durations::formatHm) ?: "") }
    var flights by rememberSaveable { mutableStateOf(g.logbookTotalFlights?.toString() ?: "") }
    var date by remember { mutableStateOf(g.logbookTotalDate?.let(LocalDate::parse) ?: LocalDate.now()) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var pickDate by rememberSaveable { mutableStateOf(false) }

    fun save(minutes: Int?, count: Int?) = scope.launch {
        when (val r = app.repository.saveLogbookTotal(g.id, minutes, count, if (minutes == null) null else date)) {
            is SaveResult.Saved -> onDismiss()
            is SaveResult.Rejected -> error = r.message
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Logbook total for ${g.registration}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(hours, { hours = it; error = null }, label = { Text("Hours") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    supportingText = { Text("Digits, H:MM or decimal hours") }, visualTransformation = ColonTransformation)
                OutlinedTextField(flights, { flights = it.filter(Char::isDigit); error = null }, label = { Text("Flights (optional)") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Box(Modifier.fillMaxWidth()) {
                    OutlinedTextField(date.shortText(), {}, readOnly = true, label = { Text("As of") }, modifier = Modifier.fillMaxWidth())
                    Box(Modifier.matchParentSize().clickable { pickDate = true })
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val m = LogbookCalculator.parseHours(hours)
                when {
                    hours.isBlank() -> error = "Enter the hours, or use Clear"
                    m == null -> error = "Enter hours as digits, H:MM or decimal hours"
                    else -> save(m, flights.toIntOrNull())
                }
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { save(null, null) }) { Text("Clear") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )

    if (pickDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    pickDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
}

/** Glider rows in dropdowns read like `FQM, Hornet`. */
fun GliderEntity.pickerLabel(): String = if (type.isBlank()) registration else "$registration, $type"
