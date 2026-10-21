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
import androidx.compose.material3.AssistChip
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
import io.github.jgfyyvzss.gliderpilotlog.ui.PickerField
import io.github.jgfyyvzss.gliderpilotlog.ui.PickerOption
import io.github.jgfyyvzss.gliderpilotlog.ui.appContainer
import io.github.jgfyyvzss.gliderpilotlog.ui.gliders.pickerLabel
import io.github.jgfyyvzss.gliderpilotlog.ui.shortText
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private const val ANY = ""

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
        filter.gliderId?.let { id -> add(stringResource(R.string.filter_chip_glider, gliders.firstOrNull { it.id == id }?.registration ?: "?") to filter.copy(gliderId = null)) }
        filter.airfieldId?.let { id -> add((airfields.firstOrNull { it.id == id }?.name ?: stringResource(R.string.flight_airfield)) to filter.copy(airfieldId = null)) }
        filter.clubId?.let { id -> add((clubs.firstOrNull { it.id == id }?.name ?: stringResource(R.string.flight_club)) to filter.copy(clubId = null)) }
        filter.dateFrom?.let { add(stringResource(R.string.filter_chip_from, it.shortText()) to filter.copy(dateFrom = null)) }
        filter.dateTo?.let { add(stringResource(R.string.filter_chip_to, it.shortText()) to filter.copy(dateTo = null)) }
        filter.role?.let { add(it.label() to filter.copy(role = null)) }
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
    val any = PickerOption(ANY, stringResource(R.string.common_any))
    val numeric = KeyboardOptions(keyboardType = KeyboardType.Number)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.filter_title), style = MaterialTheme.typography.titleMedium)
            PickerField(stringResource(R.string.flight_glider), draft.gliderId ?: ANY, listOf(any) + gliderOptions, { draft = draft.copy(gliderId = it.ifEmpty { null }) })
            PickerField(stringResource(R.string.flight_airfield), draft.airfieldId ?: ANY, listOf(any) + airfieldOptions, { draft = draft.copy(airfieldId = it.ifEmpty { null }) })
            PickerField(stringResource(R.string.flight_club), draft.clubId ?: ANY, listOf(any) + clubOptions, { draft = draft.copy(clubId = it.ifEmpty { null }) })

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DateField(stringResource(R.string.filter_from), draft.dateFrom, Modifier.weight(1f)) { pickDate = "from" }
                DateField(stringResource(R.string.filter_to), draft.dateTo, Modifier.weight(1f)) { pickDate = "to" }
            }

            Text(stringResource(R.string.totals_role), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(draft.role == null, { draft = draft.copy(role = null) }, label = { Text(stringResource(R.string.common_any)) })
                Role.entries.forEach { r ->
                    FilterChip(draft.role == r, { draft = draft.copy(role = r) }, label = { Text(r.label()) })
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
                OutlinedButton(onClick = { onApply(FlightFilter()) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.filter_clear_all)) }
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
