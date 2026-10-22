package io.github.jgfyyvzss.gliderpilotlog.ui.flights

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.jgfyyvzss.gliderpilotlog.R
import io.github.jgfyyvzss.gliderpilotlog.ui.label
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jgfyyvzss.gliderpilotlog.core.ClockTime
import io.github.jgfyyvzss.gliderpilotlog.core.FlightFilter
import io.github.jgfyyvzss.gliderpilotlog.core.Role
import io.github.jgfyyvzss.gliderpilotlog.data.Settings
import io.github.jgfyyvzss.gliderpilotlog.ui.ColonTransformation
import io.github.jgfyyvzss.gliderpilotlog.ui.PickerOption
import io.github.jgfyyvzss.gliderpilotlog.ui.appContainer
import io.github.jgfyyvzss.gliderpilotlog.ui.gliders.pickerLabel
import io.github.jgfyyvzss.gliderpilotlog.ui.shortText
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Active filters as removable chips, after a Filter chip that opens the filter sheet. Shared by Flights and Totals. */
@Composable
fun FilterBar(modifier: Modifier = Modifier) {
    val app = appContainer()
    val filter by app.filter.filter.collectAsStateWithLifecycle()
    val settings by app.settings.settings.collectAsStateWithLifecycle(Settings())
    val gliders by remember { app.repository.observeGliders() }.collectAsStateWithLifecycle(emptyList())
    val airfields by remember { app.repository.observeAirfields() }.collectAsStateWithLifecycle(emptyList())
    val clubs by remember { app.repository.observeClubs() }.collectAsStateWithLifecycle(emptyList())
    var open by rememberSaveable { mutableStateOf(false) }

    val chips = buildList<Pair<String, FlightFilter>> {
        // One chip per field, naming every choice, for example "Glider FQM, GZY".
        if (filter.gliderIds.isNotEmpty()) {
            val names = gliders.filter { it.id in filter.gliderIds }.joinToString(", ") { it.registration }.ifEmpty { "?" }
            add(stringResource(R.string.filter_chip_glider, names) to filter.copy(gliderIds = emptySet()))
        }
        if (filter.airfieldIds.isNotEmpty()) {
            val names = airfields.filter { it.id in filter.airfieldIds }.joinToString(", ") { it.name }.ifEmpty { "?" }
            add(stringResource(R.string.filter_chip_airfield, names) to filter.copy(airfieldIds = emptySet()))
        }
        if (filter.clubIds.isNotEmpty()) {
            val names = clubs.filter { it.id in filter.clubIds }.joinToString(", ") { it.name }.ifEmpty { "?" }
            add(stringResource(R.string.filter_chip_club, names) to filter.copy(clubIds = emptySet()))
        }
        filter.dateFrom?.let { add(stringResource(R.string.filter_chip_from, it.shortText()) to filter.copy(dateFrom = null)) }
        filter.dateTo?.let { add(stringResource(R.string.filter_chip_to, it.shortText()) to filter.copy(dateTo = null)) }
        if (filter.roles.isNotEmpty()) {
            add(Role.entries.filter { it in filter.roles }.map { it.label() }.joinToString(", ") to filter.copy(roles = emptySet()))
        }
        if (filter.logNoFrom != null || filter.logNoTo != null) {
            add(stringResource(R.string.filter_chip_log, filter.logNoFrom?.toString() ?: "", filter.logNoTo?.toString() ?: "") to filter.copy(logNoFrom = null, logNoTo = null))
        }
        if (filter.launchFrom != null || filter.launchTo != null) {
            add(stringResource(R.string.filter_chip_launch, filter.launchFrom?.toString() ?: "", filter.launchTo?.toString() ?: "") to filter.copy(launchFrom = null, launchTo = null))
        }
    }

    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AssistChip(
            onClick = { open = true },
            label = { Text(stringResource(R.string.filter_button)) },
            leadingIcon = { Icon(Icons.Default.FilterList, contentDescription = null) },
        )
        chips.forEach { (label, without) ->
            InputChip(
                selected = true,
                onClick = { app.filter.set(without) },
                label = { Text(label) },
                trailingIcon = { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.filter_chip_remove, label)) },
            )
        }
    }

    if (open) {
        FilterSheet(
            initial = filter,
            showLaunchTimes = settings.recordTimes || filter.launchFrom != null || filter.launchTo != null,
            gliderOptions = gliders.map { PickerOption(it.id, it.pickerLabel()) },
            airfieldOptions = airfields.map { PickerOption(it.id, it.name) },
            clubOptions = clubs.map { PickerOption(it.id, it.name) },
            onApply = { app.filter.set(it); open = false },
            onDismiss = { open = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FilterSheet(
    initial: FlightFilter,
    showLaunchTimes: Boolean,
    gliderOptions: List<PickerOption>,
    airfieldOptions: List<PickerOption>,
    clubOptions: List<PickerOption>,
    onApply: (FlightFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(initial) }
    var logFrom by remember { mutableStateOf(initial.logNoFrom?.toString() ?: "") }
    var logTo by remember { mutableStateOf(initial.logNoTo?.toString() ?: "") }
    var launchFrom by remember { mutableStateOf(initial.launchFrom?.toString() ?: "") }
    var launchTo by remember { mutableStateOf(initial.launchTo?.toString() ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    val badTime = stringResource(R.string.filter_bad_time)
    val datesReversed = stringResource(R.string.filter_dates_reversed)
    var pickDate by remember { mutableStateOf<String?>(null) } // "from" or "to"
    val numeric = KeyboardOptions(keyboardType = KeyboardType.Number)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.filter_title), style = MaterialTheme.typography.titleMedium)
            MultiPickerField(stringResource(R.string.flight_glider), draft.gliderIds, gliderOptions) { draft = draft.copy(gliderIds = it) }
            MultiPickerField(stringResource(R.string.flight_airfield), draft.airfieldIds, airfieldOptions) { draft = draft.copy(airfieldIds = it) }
            MultiPickerField(stringResource(R.string.flight_club), draft.clubIds, clubOptions) { draft = draft.copy(clubIds = it) }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DateField(stringResource(R.string.filter_from), draft.dateFrom, Modifier.weight(1f)) { pickDate = "from" }
                DateField(stringResource(R.string.filter_to), draft.dateTo, Modifier.weight(1f)) { pickDate = "to" }
            }

            Text(stringResource(R.string.totals_role), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Any clears the choice; each role chip adds or removes that role.
                FilterChip(draft.roles.isEmpty(), { draft = draft.copy(roles = emptySet()) }, label = { Text(stringResource(R.string.common_any)) })
                Role.entries.forEach { r ->
                    FilterChip(r in draft.roles, { draft = draft.copy(roles = if (r in draft.roles) draft.roles - r else draft.roles + r) }, label = { Text(r.label()) })
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(logFrom, { logFrom = it.filter(Char::isDigit); error = null }, label = { Text(stringResource(R.string.filter_log_from)) },
                    singleLine = true, keyboardOptions = numeric, modifier = Modifier.weight(1f))
                OutlinedTextField(logTo, { logTo = it.filter(Char::isDigit); error = null }, label = { Text(stringResource(R.string.filter_log_to)) },
                    singleLine = true, keyboardOptions = numeric, modifier = Modifier.weight(1f))
            }

            if (showLaunchTimes) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(launchFrom, { launchFrom = it; error = null }, label = { Text(stringResource(R.string.filter_launch_from)) },
                        placeholder = { Text("10:00") }, singleLine = true, keyboardOptions = numeric, visualTransformation = ColonTransformation, modifier = Modifier.weight(1f))
                    OutlinedTextField(launchTo, { launchTo = it; error = null }, label = { Text(stringResource(R.string.filter_launch_to)) },
                        placeholder = { Text("12:00") }, singleLine = true, keyboardOptions = numeric, visualTransformation = ColonTransformation, modifier = Modifier.weight(1f))
                }
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onApply(FlightFilter()) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.filter_clear_all)) }
                Button(onClick = {
                    val lt = parseTime(launchTo)
                    val lf = parseTime(launchFrom)
                    when {
                        lf == BAD || lt == BAD -> error = badTime
                        draft.dateFrom != null && draft.dateTo != null && draft.dateFrom!! > draft.dateTo!! ->
                            error = datesReversed
                        else -> onApply(
                            draft.copy(
                                logNoFrom = logFrom.toIntOrNull(), logNoTo = logTo.toIntOrNull(),
                                launchFrom = lf as ClockTime?, launchTo = lt as ClockTime?,
                            ),
                        )
                    }
                }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.filter_apply)) }
            }
        }
    }

    pickDate?.let { which ->
        val current = if (which == "from") draft.dateFrom else draft.dateTo
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (current ?: LocalDate.now()).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { pickDate = null },
            confirmButton = {
                TextButton(onClick = {
                    val d = state.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    draft = if (which == "from") draft.copy(dateFrom = d) else draft.copy(dateTo = d)
                    pickDate = null
                }) { Text(stringResource(R.string.common_ok)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    draft = if (which == "from") draft.copy(dateFrom = null) else draft.copy(dateTo = null)
                    pickDate = null
                }) { Text(stringResource(R.string.common_any)) }
            },
        ) { DatePicker(state) }
    }
}

/** A read-only field listing the chosen options, or Any, that opens a list with a tick box for each. */
@Composable
private fun MultiPickerField(label: String, selected: Set<String>, options: List<PickerOption>, onChange: (Set<String>) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val shown = options.filter { it.id in selected }.joinToString(", ") { it.label }.ifEmpty { stringResource(R.string.common_any) }
    Box {
        OutlinedTextField(shown, {}, readOnly = true, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Box(Modifier.matchParentSize().clickable { open = true })
    }
    if (open) {
        var ticked by remember { mutableStateOf(selected) }
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(label) },
            text = {
                if (options.isEmpty()) {
                    Text(stringResource(R.string.picker_empty))
                } else {
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(options, key = { it.id }) { o ->
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .clickable { ticked = if (o.id in ticked) ticked - o.id else ticked + o.id },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = o.id in ticked, onCheckedChange = null)
                                Text(o.label, Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { onChange(ticked); open = false }) { Text(stringResource(R.string.common_ok)) } },
            dismissButton = { TextButton(onClick = { onChange(emptySet()); open = false }) { Text(stringResource(R.string.common_any)) } },
        )
    }
}

private object BAD

/** Null for blank, [BAD] for anything unreadable, otherwise the time. */
private fun parseTime(text: String): Any? = if (text.isBlank()) null else ClockTime.parseFormEntry(text) ?: BAD

@Composable
private fun DateField(label: String, date: LocalDate?, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier) {
        OutlinedTextField(date?.shortText() ?: "", {}, readOnly = true, label = { Text(label) }, placeholder = { Text(stringResource(R.string.common_any)) },
            modifier = Modifier.fillMaxWidth())
        Box(Modifier.matchParentSize().clickable(onClick = onClick))
    }
}
