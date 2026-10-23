package io.github.jgfyyvzss.soarlog.ui.logbook

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.jgfyyvzss.soarlog.R
import io.github.jgfyyvzss.soarlog.ui.UiText
import io.github.jgfyyvzss.soarlog.ui.gliders.pickerLabel
import io.github.jgfyyvzss.soarlog.ui.text
import io.github.jgfyyvzss.soarlog.ui.uiText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.jgfyyvzss.soarlog.core.Durations
import io.github.jgfyyvzss.soarlog.core.HoursDisplay
import io.github.jgfyyvzss.soarlog.core.HoursFormat
import io.github.jgfyyvzss.soarlog.core.LogbookCalc
import io.github.jgfyyvzss.soarlog.core.LogbookCalculator
import io.github.jgfyyvzss.soarlog.data.FlightEntity
import io.github.jgfyyvzss.soarlog.data.GliderEntity
import io.github.jgfyyvzss.soarlog.data.LogbookRepository
import io.github.jgfyyvzss.soarlog.data.SaveResult
import io.github.jgfyyvzss.soarlog.data.Settings
import io.github.jgfyyvzss.soarlog.ui.ColonTransformation
import io.github.jgfyyvzss.soarlog.ui.appContainer
import io.github.jgfyyvzss.soarlog.ui.shortText
import io.github.jgfyyvzss.soarlog.ui.vmFactory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.launch

/**
 * The calculator's state. With a [day], the day's flights for the glider are listed and ticked; without one it is
 * the manual calculator from the Gliders screen, and the pilot picks the date the new total applies to.
 */
class LogbookCalculatorViewModel(private val repo: LogbookRepository, private val gliderId: String, private val day: LocalDate?) : ViewModel() {
    var glider by mutableStateOf<GliderEntity?>(null)
        private set
    var flights by mutableStateOf<List<FlightEntity>>(emptyList())
        private set
    var ticked by mutableStateOf<Set<String>>(emptySet())
        private set
    var dayText by mutableStateOf("")
    var dayFlightsText by mutableStateOf("")
    var extraText by mutableStateOf("")
    var extraFlightsText by mutableStateOf("")
    var beforeText by mutableStateOf("")
    var beforeFlightsText by mutableStateOf("")
    var date by mutableStateOf(day ?: LocalDate.now())
    var error by mutableStateOf<UiText?>(null)
    /** Set once the pilot has seen the double-add warning for the current figures. */
    var warningSeen by mutableStateOf(false)
        private set
    var saved by mutableStateOf(false)
        private set

    val isManual get() = day == null

    init {
        viewModelScope.launch {
            val g = repo.glider(gliderId)
            glider = g
            beforeText = g?.logbookTotalMinutes?.let(Durations::formatHm) ?: ""
            beforeFlightsText = g?.logbookTotalFlights?.toString() ?: ""
            if (day != null) {
                flights = repo.flightsOnDay(gliderId, day)
                selectFlights(flights.map { it.id }.toSet())
            }
        }
    }

    /** Ticking updates both day figures, replacing anything typed there. */
    fun selectFlights(ids: Set<String>) {
        ticked = ids
        val chosen = flights.filter { it.id in ids }
        dayText = if (chosen.isEmpty() && isManual) "" else Durations.formatHm(chosen.sumOf { it.durationMinutes })
        dayFlightsText = if (chosen.isEmpty() && isManual) "" else chosen.size.toString()
        changed()
    }

    fun toggle(id: String) = selectFlights(if (id in ticked) ticked - id else ticked + id)

    fun changed() {
        error = null
        warningSeen = false
    }

    /** The figures, or null with [error] set when one cannot be read. */
    fun calc(): LogbookCalc? {
        val day = LogbookCalculator.parseHours(dayText)
        val extra = LogbookCalculator.parseHours(extraText)
        val before = LogbookCalculator.parseHours(beforeText)
        val dayCount = LogbookCalculator.parseCount(dayFlightsText)
        val extraCount = LogbookCalculator.parseCount(extraFlightsText)
        val beforeCount = if (beforeFlightsText.isBlank()) null else LogbookCalculator.parseCount(beforeFlightsText)
        if (day == null || extra == null || before == null) return null
        if (dayCount == null || extraCount == null || (beforeFlightsText.isNotBlank() && beforeCount == null)) return null
        return LogbookCalc(day, dayCount, extra, extraCount, before, beforeCount)
    }

    val mayAlreadyInclude: Boolean
        get() = LogbookCalculator.mayAlreadyInclude(glider?.logbookTotalDate?.let(LocalDate::parse), date)

    fun save() {
        val c = calc() ?: run { error = uiText(R.string.logbook_bad_figures); return }
        if (beforeText.isBlank()) { error = uiText(R.string.logbook_need_before); return }
        if (mayAlreadyInclude && !warningSeen) { warningSeen = true; return }
        viewModelScope.launch {
            when (val r = repo.saveLogbookTotal(gliderId, c.newMinutes, c.newFlights, date)) {
                is SaveResult.Saved -> saved = true
                is SaveResult.Rejected -> error = UiText.Message(r.message)
            }
        }
    }
}

/** The aircraft logbook calculator as a sheet. [day] null opens the manual calculator. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogbookCalculatorSheet(gliderId: String, day: LocalDate?, onDismiss: () -> Unit) {
    val vm: LogbookCalculatorViewModel = viewModel(
        key = "logbook-$gliderId-$day",
        factory = vmFactory { LogbookCalculatorViewModel(it.repository, gliderId, day) },
    )
    val settings by appContainer().settings.settings.collectAsStateWithLifecycle(Settings())
    var pickDate by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(vm.saved) { if (vm.saved) onDismiss() }
    val display = settings.hoursDisplay

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val g = vm.glider ?: return@Column
            // Closing is the standard ✕ in the corner, so Save has the full width in every language.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.logbook_title, g.pickerLabel()), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_close)) }
            }
            Text(stringResource(R.string.logbook_intro),
                style = MaterialTheme.typography.bodySmall)

            if (!vm.isManual) {
                Text(stringResource(R.string.logbook_flights_on, vm.date.shortText()), style = MaterialTheme.typography.titleSmall)
                vm.flights.forEach { f ->
                    Row(Modifier.fillMaxWidth().clickable { vm.toggle(f.id) }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = f.id in vm.ticked, onCheckedChange = { vm.toggle(f.id) })
                        Text(
                            listOfNotNull("${f.logNo}", f.launchTime, HoursFormat.format(f.durationMinutes, display), f.picName).joinToString(" · "),
                        )
                    }
                }
            }

            // A small spreadsheet, like the sum on paper: each row's flights and hours, then the total.
            val c = vm.calc()
            TableRow(
                label = { },
                flights = { Text(stringResource(R.string.logbook_col_flights), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth()) },
                hours = { Text(stringResource(R.string.common_hours), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth()) },
            )
            FigureRow(stringResource(R.string.logbook_row_day), vm.dayFlightsText, { vm.dayFlightsText = it; vm.changed() }, vm.dayText, { vm.dayText = it; vm.changed() })
            FigureRow(stringResource(R.string.logbook_row_other), vm.extraFlightsText, { vm.extraFlightsText = it; vm.changed() }, vm.extraText, { vm.extraText = it; vm.changed() })
            FigureRow(stringResource(R.string.logbook_row_last), vm.beforeFlightsText, { vm.beforeFlightsText = it; vm.changed() }, vm.beforeText, { vm.beforeText = it; vm.changed() })
            HorizontalDivider()
            TableRow(
                label = { Text(stringResource(R.string.logbook_row_total), fontWeight = FontWeight.Bold) },
                flights = { Text(c?.newFlights?.toString() ?: "", fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth()) },
                hours = { Text(c?.let { HoursFormat.format(it.newMinutes, display) } ?: "?", fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth()) },
            )
            Text(
                g.logbookTotalDate?.let { stringResource(R.string.logbook_before_dated, LocalDate.parse(it).shortText()) }
                    ?: stringResource(R.string.logbook_before_none),
                style = MaterialTheme.typography.bodySmall,
            )

            if (vm.isManual) {
                Box(Modifier.fillMaxWidth()) {
                    OutlinedTextField(vm.date.shortText(), {}, readOnly = true, label = { Text(stringResource(R.string.logbook_applies_to)) }, modifier = Modifier.fillMaxWidth())
                    Box(Modifier.matchParentSize().clickable { pickDate = true })
                }
            }

            if (vm.mayAlreadyInclude) {
                Text(
                    stringResource(
                        R.string.logbook_double_add,
                        g.logbookTotalDate?.let { LocalDate.parse(it).shortText() } ?: "", vm.date.shortText(),
                    ),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
                )
            }
            vm.error?.let { Text(it.text(), color = MaterialTheme.colorScheme.error) }
            Button(onClick = vm::save, enabled = c != null, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (vm.mayAlreadyInclude && vm.warningSeen) R.string.flight_save_anyway else R.string.logbook_save))
            }
        }
    }

    if (pickDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = vm.date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { vm.date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate(); vm.changed() }
                    pickDate = false
                }) { Text(stringResource(R.string.common_ok)) }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text(stringResource(R.string.common_cancel)) } },
        ) { DatePicker(state) }
    }
}

/** One row of the table: a label, then the flights and hours columns, which line up on every row. */
@Composable
private fun TableRow(label: @Composable () -> Unit, flights: @Composable () -> Unit, hours: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1.1f)) { label() }
        Box(Modifier.weight(0.9f)) { flights() }
        Box(Modifier.weight(1.2f)) { hours() }
    }
}

/**
 * A row of figures to type. Hours take digits with the last two as minutes, H:MM or decimal hours; a figure that
 * cannot be read is marked, and the Total row shows what the others add up to.
 */
@Composable
private fun FigureRow(label: String, flights: String, onFlights: (String) -> Unit, hours: String, onHours: (String) -> Unit) {
    TableRow(
        label = { Text(label) },
        flights = {
            OutlinedTextField(
                flights, { onFlights(it.filter(Char::isDigit)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.End), modifier = Modifier.fillMaxWidth(),
            )
        },
        hours = {
            OutlinedTextField(
                hours, onHours, singleLine = true, isError = LogbookCalculator.parseHours(hours) == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), visualTransformation = ColonTransformation,
                textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.End), modifier = Modifier.fillMaxWidth(),
            )
        },
    )
}
