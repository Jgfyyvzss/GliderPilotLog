package io.github.jgfyyvzss.gliderpilotlog.ui.weglide

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.jgfyyvzss.gliderpilotlog.R
import io.github.jgfyyvzss.gliderpilotlog.core.Durations
import io.github.jgfyyvzss.gliderpilotlog.core.weglide.UploadedFlight
import io.github.jgfyyvzss.gliderpilotlog.core.weglide.WeGlideCredentials
import io.github.jgfyyvzss.gliderpilotlog.data.weglide.PendingUpload
import io.github.jgfyyvzss.gliderpilotlog.ui.appContainer
import io.github.jgfyyvzss.gliderpilotlog.ui.shortText
import io.github.jgfyyvzss.gliderpilotlog.ui.text
import io.github.jgfyyvzss.gliderpilotlog.ui.vmFactory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.launch

/**
 * Upload to WeGlide, opened from Home. The file can come from the file picker or a logger download, and nothing
 * here touches the logbook, apart from storing a picked WeGlide type on a matching glider.
 */
@Composable
fun UploadScreen(onFromLogger: () -> Unit) {
    val app = appContainer()
    val vm: UploadViewModel = viewModel(
        factory = vmFactory { UploadViewModel(it.repository, it.aircraft, it.weglideApi, it.weglideCredentials, it.pendingUpload) },
    )
    val aircraftList by app.aircraft.list.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { PendingUpload.read(app.contentResolver, uri)?.let(app.pendingUpload::set) ?: vm.unreadable() }
    }
    var choosingAircraft by rememberSaveable { mutableStateOf(false) }
    val status = vm.status

    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.upload_intro), style = MaterialTheme.typography.bodyMedium)

        // The file
        Text(stringResource(R.string.upload_file), style = MaterialTheme.typography.titleMedium)
        val f = vm.file
        when {
            f != null -> {
                Text(f.name)
                vm.summary?.let { Text(summaryText(it), style = MaterialTheme.typography.bodySmall) }
            }
            status !is UploadStatus.Done -> Text(stringResource(R.string.upload_no_file))
        }
        vm.fileProblem?.let { Text(it.text(), color = MaterialTheme.colorScheme.error) }
        if (status !is UploadStatus.Done) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { pick.launch(arrayOf("*/*")) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.upload_choose_file))
                }
                FilledTonalButton(onClick = onFromLogger, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.home_from_logger)) }
            }
        }

        if (f != null) {
            HorizontalDivider()
            Text(stringResource(R.string.weglide_aircraft), style = MaterialTheme.typography.titleMedium)
            val glider = vm.matchedGlider
            Text(
                vm.aircraft?.label
                    ?: if (glider != null) stringResource(R.string.upload_glider_no_type, glider.registration)
                    else stringResource(R.string.upload_choose_aircraft_hint),
            )
            FilledTonalButton(onClick = { choosingAircraft = true }) { Text(stringResource(R.string.common_choose)) }
        }

        HorizontalDivider()
        Credentials(vm)

        if (f != null) {
            HorizontalDivider()
            Button(onClick = vm::upload, enabled = vm.canUpload, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.upload_button)) }
        }
        when (status) {
            UploadStatus.Idle -> Unit
            UploadStatus.Uploading -> {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.upload_uploading))
            }
            is UploadStatus.Failed -> {
                Text(status.message.text(), color = MaterialTheme.colorScheme.error)
                Text(stringResource(R.string.upload_failed_hint), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = vm::upload, enabled = vm.canUpload) { Text(stringResource(R.string.logger_try_again)) }
            }
            is UploadStatus.Done -> {
                Result(status.flight)
                OutlinedButton(onClick = vm::reset, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.upload_another)) }
            }
        }
    }

    if (choosingAircraft) {
        val glider = vm.matchedGlider
        AircraftPicker(
            aircraft = aircraftList?.aircraft.orEmpty(),
            initialQuery = glider?.type ?: "",
            // A pick for a glider without a type is stored on it, so the type change is shown first.
            currentType = glider?.takeIf { it.weglideAircraftId == null }?.type,
            onDismiss = { choosingAircraft = false },
        ) { picked ->
            vm.pickAircraft(picked)
            choosingAircraft = false
        }
    }
}

@Composable
private fun summaryText(s: FileSummary): String {
    val first = s.first ?: return stringResource(R.string.upload_no_flight_found)
    val zone = ZoneId.systemDefault()
    val line = stringResource(
        R.string.upload_summary,
        first.date(zone).shortText(),
        first.launchTime(zone).toString(),
        Durations.formatHm(first.durationMinutes),
        s.gliderIdHeader ?: "?",
    )
    return if (s.flights > 1) line + "\n" + stringResource(R.string.upload_several_flights, s.flights) else line
}

/** The WeGlide ID and date of birth: asked for when missing, and otherwise only shown as saved. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Credentials(vm: UploadViewModel) {
    Text(stringResource(R.string.upload_account), style = MaterialTheme.typography.titleMedium)
    if (!vm.credentialsLoaded) return
    if (!vm.editingCredentials) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.upload_account_saved), Modifier.weight(1f))
            TextButton(onClick = vm::editCredentials) { Text(stringResource(R.string.upload_change)) }
        }
        return
    }
    val saved = vm.credentials
    var userId by rememberSaveable { mutableStateOf(saved?.userId ?: "") }
    var dob by rememberSaveable { mutableStateOf(saved?.dateOfBirth?.toString() ?: "") }
    var pickDate by rememberSaveable { mutableStateOf(false) }
    val date = runCatching { LocalDate.parse(dob) }.getOrNull()

    OutlinedTextField(
        userId, { userId = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.upload_weglide_id)) },
        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
        supportingText = { Text(stringResource(R.string.upload_weglide_id_hint)) },
    )
    Box {
        OutlinedTextField(
            date?.shortText() ?: "", {}, readOnly = true, label = { Text(stringResource(R.string.upload_date_of_birth)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Box(Modifier.matchParentSize().clickable { pickDate = true })
    }
    Text(stringResource(R.string.upload_account_privacy), style = MaterialTheme.typography.bodySmall)
    Button(
        onClick = { date?.let { vm.saveCredentials(userId, it) } },
        enabled = WeGlideCredentials.validUserId(userId) && date != null,
    ) { Text(stringResource(R.string.common_save)) }

    if (pickDate) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date?.atStartOfDay()?.toInstant(ZoneOffset.UTC)?.toEpochMilli(),
            initialDisplayMode = DisplayMode.Input,
        )
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { dob = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() }
                    pickDate = false
                }) { Text(stringResource(R.string.common_ok)) }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text(stringResource(R.string.common_cancel)) } },
        ) { DatePicker(state) }
    }
}

/** What WeGlide reported for the new flight. */
@Composable
private fun Result(flight: UploadedFlight?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.upload_done), style = MaterialTheme.typography.titleMedium)
            if (flight == null) {
                Text(stringResource(R.string.upload_done_no_details))
                return@Column
            }
            listOfNotNull(
                flight.id?.let { stringResource(R.string.upload_result_flight, it.toString()) },
                flight.scoringDate?.let { stringResource(R.string.upload_result_date, it) },
                flight.pilotName?.let { stringResource(R.string.upload_result_pilot, it) },
                flight.aircraftName?.let { stringResource(R.string.upload_result_aircraft, it) },
                flight.registration?.let { stringResource(R.string.upload_result_registration, it) },
                flight.competitionId?.let { stringResource(R.string.upload_result_competition_id, it) },
            ).forEach { Text(it) }
        }
    }
}
