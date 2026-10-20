package io.github.jgfyyvzss.gliderpilotlog.ui.logbook

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
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.jgfyyvzss.gliderpilotlog.core.Durations
import io.github.jgfyyvzss.gliderpilotlog.core.HoursDisplay
import io.github.jgfyyvzss.gliderpilotlog.core.HoursFormat
import io.github.jgfyyvzss.gliderpilotlog.core.LogbookCalc
import io.github.jgfyyvzss.gliderpilotlog.core.LogbookCalculator
import io.github.jgfyyvzss.gliderpilotlog.data.FlightEntity
import io.github.jgfyyvzss.gliderpilotlog.data.GliderEntity
import io.github.jgfyyvzss.gliderpilotlog.data.LogbookRepository
import io.github.jgfyyvzss.gliderpilotlog.data.SaveResult
import io.github.jgfyyvzss.gliderpilotlog.data.Settings
import io.github.jgfyyvzss.gliderpilotlog.ui.ColonTransformation
import io.github.jgfyyvzss.gliderpilotlog.ui.appContainer
import io.github.jgfyyvzss.gliderpilotlog.ui.shortText
import io.github.jgfyyvzss.gliderpilotlog.ui.vmFactory
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
    var error by mutableStateOf<String?>(null)
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
        val c = calc() ?: run { error = "Enter hours as digits, H:MM or decimal hours, and flights as whole numbers"; return }
        if (beforeText.isBlank()) { error = "Enter the logbook total before this flying"; return }
        if (mayAlreadyInclude && !warningSeen) { warningSeen = true; return }
        viewModelScope.launch {
            when (val r = repo.saveLogbookTotal(gliderId, c.newMinutes, c.newFlights, date)) {
                is SaveResult.Saved -> saved = true
                is SaveResult.Rejected -> error = r.message
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
    val decimal = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    val number = KeyboardOptions(keyboardType = KeyboardType.Number)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val g = vm.glider ?: return@Column
            Text("Aircraft logbook: ${g.registration}${if (g.type.isNotBlank()) ", ${g.type}" else ""}", style = MaterialTheme.typography.titleMedium)
            Text("Adds a day's flying to the total in the glider's own logbook. Your own totals are not changed.",
                style = MaterialTheme.typography.bodySmall)

            if (!vm.isManual) {
                Text("Flights on ${vm.date.shortText()}", style = MaterialTheme.typography.titleSmall)
                vm.flights.forEach { f ->
                    Row(Modifier.fillMaxWidth().clickable { vm.toggle(f.id) }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = f.id in vm.ticked, onCheckedChange = { vm.toggle(f.id) })
                        Text(
                            listOfNotNull("${f.logNo}", f.launchTime, HoursFormat.format(f.durationMinutes, display), f.picName).joinToString(" · "),
                        )
                    }
                }
            }

            Text(if (vm.isManual) "Flying to add" else "Day total", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(vm.dayText, { vm.dayText = it; vm.changed() }, label = { Text("Hours") }, singleLine = true,
                    keyboardOptions = decimal, supportingText = hint(vm.dayText, display), visualTransformation = ColonTransformation, modifier = Modifier.weight(1f))
                OutlinedTextField(vm.dayFlightsText, { vm.dayFlightsText = it.filter(Char::isDigit); vm.changed() }, label = { Text("Flights") },
                    singleLine = true, keyboardOptions = number, modifier = Modifier.weight(1f))
            }
            Text("Extra flying not in this app", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(vm.extraText, { vm.extraText = it; vm.changed() }, label = { Text("Hours") }, singleLine = true,
                    keyboardOptions = decimal, supportingText = hint(vm.extraText, display), visualTransformation = ColonTransformation, modifier = Modifier.weight(1f))
                OutlinedTextField(vm.extraFlightsText, { vm.extraFlightsText = it.filter(Char::isDigit); vm.changed() }, label = { Text("Flights") },
                    singleLine = true, keyboardOptions = number, modifier = Modifier.weight(1f))
            }

            Text("Logbook total before", style = MaterialTheme.typography.titleSmall)
            Text(
                g.logbookTotalDate?.let { "Last entered for ${LocalDate.parse(it).shortText()}. Overwrite it if the aircraft logbook differs." }
                    ?: "No total stored yet. Enter the last total from the aircraft logbook.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(vm.beforeText, { vm.beforeText = it; vm.changed() }, label = { Text("Hours") }, singleLine = true,
                    keyboardOptions = decimal, supportingText = hint(vm.beforeText, display), visualTransformation = ColonTransformation, modifier = Modifier.weight(1f))
                OutlinedTextField(vm.beforeFlightsText, { vm.beforeFlightsText = it.filter(Char::isDigit); vm.changed() },
                    label = { Text("Flights (optional)") }, singleLine = true, keyboardOptions = number, modifier = Modifier.weight(1f))
            }

            if (vm.isManual) {
                Box(Modifier.fillMaxWidth()) {
                    OutlinedTextField(vm.date.shortText(), {}, readOnly = true, label = { Text("The new total applies to") }, modifier = Modifier.fillMaxWidth())
                    Box(Modifier.matchParentSize().clickable { pickDate = true })
                }
            }

            HorizontalDivider()
            val c = vm.calc()
            Text(
                if (c == null) "New total: check the figures above"
                else "New total: ${HoursFormat.format(c.newMinutes, display)}" + (c.newFlights?.let { ", $it flights" } ?: ""),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
            )
            if (vm.mayAlreadyInclude) {
                Text(
                    "The stored total is dated ${g.logbookTotalDate?.let { LocalDate.parse(it).shortText() }}, on or after " +
                        "${vm.date.shortText()}, so this day may already be included. Adding a day twice corrupts the total.",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
                )
            }
            vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Close") }
                Button(onClick = vm::save, enabled = c != null, modifier = Modifier.weight(1f)) {
                    Text(if (vm.mayAlreadyInclude && vm.warningSeen) "Save anyway" else "Save new total")
                }
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
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
}

/** Shows what a typed figure means, for example 123456 as = 1234:56. */
private fun hint(text: String, display: HoursDisplay): (@Composable () -> Unit)? {
    if (text.isBlank()) return null
    val m = LogbookCalculator.parseHours(text) ?: return { Text("Digits, H:MM or decimal hours") }
    val shown = HoursFormat.format(m, display)
    // The field already shows the colon, so the result is only spelled out when it reads differently.
    if (ColonTransformation.display(text.trim()) == shown) return null
    return { Text("= $shown") }
}
