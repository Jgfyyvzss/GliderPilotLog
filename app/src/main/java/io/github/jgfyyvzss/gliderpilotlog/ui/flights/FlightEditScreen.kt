package io.github.jgfyyvzss.gliderpilotlog.ui.flights

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.jgfyyvzss.gliderpilotlog.core.FlightField
import io.github.jgfyyvzss.gliderpilotlog.core.LandingChoice
import io.github.jgfyyvzss.gliderpilotlog.core.Role
import io.github.jgfyyvzss.gliderpilotlog.ui.PickerField
import io.github.jgfyyvzss.gliderpilotlog.ui.PickerOption
import io.github.jgfyyvzss.gliderpilotlog.ui.gliders.pickerLabel
import io.github.jgfyyvzss.gliderpilotlog.ui.shortText
import io.github.jgfyyvzss.gliderpilotlog.ui.vmFactory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun FlightEditScreen(id: String?, onDone: () -> Unit) {
    val vm: FlightEditViewModel = viewModel(factory = vmFactory { FlightEditViewModel(it.repository, it.settings, id) })
    LaunchedEffect(vm.done) { if (vm.done) onDone() }
    if (!vm.loaded) return

    val settings by vm.settings.collectAsStateWithLifecycle()
    val gliders by vm.gliders.collectAsStateWithLifecycle()
    val airfields by vm.airfields.collectAsStateWithLifecycle()
    val launchTypes by vm.launchTypes.collectAsStateWithLifecycle()
    val d = vm.draft
    val e = vm.errors
    var pickDate by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    // Archived records are hidden, except one this flight already uses.
    val gliderOptions = gliders.filter { !it.archived || it.id == d.gliderId }.map { PickerOption(it.id, it.pickerLabel()) }
    val airfieldOptions = airfields.filter { !it.archived || it.id == d.airfieldId || it.id == d.landingAirfieldId }
        .map { PickerOption(it.id, it.name) }
    val launchOptions = launchTypes.filter { !it.archived || it.id == d.launchTypeId }.map { PickerOption(it.id, it.name) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                d.logNoText, vm::setLogNo, label = { Text("Log no.") }, singleLine = true,
                isError = FlightField.LOG_NO in e,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            Box(Modifier.weight(1.4f)) {
                OutlinedTextField(
                    d.date?.shortText() ?: "", {}, readOnly = true, label = { Text("Date") },
                    isError = FlightField.DATE in e, modifier = Modifier.fillMaxWidth(),
                )
                Box(Modifier.matchParentSize().clickable { pickDate = true })
            }
        }
        (e[FlightField.LOG_NO] ?: e[FlightField.DATE])?.let { ErrorText(it) }

        PickerField("Glider", d.gliderId, gliderOptions, vm::setGlider, error = e[FlightField.GLIDER])
        PickerField("Airfield", d.airfieldId, airfieldOptions, vm::setAirfield, error = e[FlightField.AIRFIELD])
        if (vm.airfieldClubs.size > 1) {
            PickerField("Club", d.clubId, vm.airfieldClubs.map { PickerOption(it.id, it.name) }, vm::setClub, error = e[FlightField.CLUB])
        } else {
            e[FlightField.CLUB]?.let { ErrorText(it) }
        }
        PickerField("Launch type", d.launchTypeId, launchOptions, vm::setLaunchType, error = e[FlightField.LAUNCH_TYPE])

        Text("Role", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = d.role == Role.PIC, onClick = { vm.setRole(Role.PIC) }, label = { Text("PIC") })
            FilterChip(selected = d.role.isDual, onClick = { if (!d.role.isDual) vm.setRole(Role.STUDENT) }, label = { Text("Dual") })
        }
        if (d.role.isDual) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Role.dualRoles.forEach { r ->
                    FilterChip(selected = d.role == r, onClick = { vm.setRole(r) }, label = { Text(r.label) })
                }
            }
        }
        OutlinedTextField(
            d.picName, vm::setPicName, label = { Text("Pilot in command") }, singleLine = true,
            isError = FlightField.PIC_NAME in e, supportingText = e[FlightField.PIC_NAME]?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            modifier = Modifier.fillMaxWidth(),
        )

        if (settings.recordTimes) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    d.launchTimeText, vm::setLaunchTime, label = { Text("Launch") }, placeholder = { Text("HH:MM") },
                    singleLine = true, isError = FlightField.LAUNCH_TIME in e, modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    d.landingTimeText, vm::setLandingTime, label = { Text("Landing") }, placeholder = { Text("HH:MM") },
                    singleLine = true, isError = FlightField.LANDING_TIME in e, modifier = Modifier.weight(1f),
                )
            }
            (e[FlightField.LAUNCH_TIME] ?: e[FlightField.LANDING_TIME])?.let { ErrorText(it) }
        }

        OutlinedTextField(
            d.durationText, vm::setDuration, label = { Text("Duration") }, placeholder = { Text("H:MM") },
            singleLine = true, isError = FlightField.DURATION in e,
            supportingText = { Text(e[FlightField.DURATION] ?: "Hours and minutes, for example 1:75 is 2:15") },
            modifier = Modifier.fillMaxWidth(),
        )

        if (settings.recordLandingPlace) {
            Text("Landing place", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(d.landingChoice == LandingChoice.SAME_AS_DEPARTURE, { vm.setLandingChoice(LandingChoice.SAME_AS_DEPARTURE) }, label = { Text("Same as departure") })
                FilterChip(d.landingChoice == LandingChoice.AIRFIELD, { vm.setLandingChoice(LandingChoice.AIRFIELD) }, label = { Text("Airfield") })
                FilterChip(d.landingChoice == LandingChoice.OTHER, { vm.setLandingChoice(LandingChoice.OTHER) }, label = { Text("Other place") })
            }
            when (d.landingChoice) {
                LandingChoice.AIRFIELD -> PickerField(
                    "Landing airfield", d.landingAirfieldId, airfieldOptions, vm::setLandingAirfield,
                    error = e[FlightField.LANDING_AIRFIELD],
                )
                LandingChoice.OTHER -> OutlinedTextField(
                    d.landingPlace, vm::setLandingPlace, label = { Text("Where the flight ended") },
                    isError = FlightField.LANDING_PLACE in e, supportingText = e[FlightField.LANDING_PLACE]?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                LandingChoice.SAME_AS_DEPARTURE -> Unit
            }
        }

        OutlinedTextField(
            d.notes, vm::setNotes, label = { Text("Notes") }, minLines = 2,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth(),
        )

        vm.warnings.forEach { Text(it, color = MaterialTheme.colorScheme.tertiary) }
        Button(onClick = { vm.save() }, modifier = Modifier.fillMaxWidth()) {
            Text(if (vm.warnings.isNotEmpty() && e.isEmpty()) "Save anyway" else "Save")
        }
        if (!vm.isNew) {
            OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) { Text("Delete flight") }
        }
    }

    if (pickDate) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (d.date ?: LocalDate.now()).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { vm.setDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    pickDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete flight ${d.logNoText}?") },
            text = { Text("Its log number becomes free if it is the highest.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ErrorText(text: String) {
    Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}
