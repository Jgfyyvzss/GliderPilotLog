package io.github.jgfyyvzss.gliderpilotlog.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.jgfyyvzss.gliderpilotlog.R
import io.github.jgfyyvzss.gliderpilotlog.SoarlogsApp
import io.github.jgfyyvzss.gliderpilotlog.core.Msg
import io.github.jgfyyvzss.gliderpilotlog.core.csv.CsvError
import io.github.jgfyyvzss.gliderpilotlog.core.csv.FlightCsv
import io.github.jgfyyvzss.gliderpilotlog.core.csv.FlightImport
import io.github.jgfyyvzss.gliderpilotlog.core.csv.ParsedAirfield
import io.github.jgfyyvzss.gliderpilotlog.core.csv.ParsedGlider
import io.github.jgfyyvzss.gliderpilotlog.core.csv.RecordImport
import io.github.jgfyyvzss.gliderpilotlog.data.Documents
import io.github.jgfyyvzss.gliderpilotlog.data.FlightImportChoices
import io.github.jgfyyvzss.gliderpilotlog.data.UndoPreview
import io.github.jgfyyvzss.gliderpilotlog.ui.PickerField
import io.github.jgfyyvzss.gliderpilotlog.ui.PickerOption
import io.github.jgfyyvzss.gliderpilotlog.ui.UiText
import io.github.jgfyyvzss.gliderpilotlog.ui.gliders.pickerLabel
import io.github.jgfyyvzss.gliderpilotlog.ui.launchTypeName
import io.github.jgfyyvzss.gliderpilotlog.ui.shortText
import io.github.jgfyyvzss.gliderpilotlog.ui.text
import io.github.jgfyyvzss.gliderpilotlog.ui.uiText
import io.github.jgfyyvzss.gliderpilotlog.ui.vmFactory
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Where the Spreadsheets screen is in an import, export or undo. */
sealed interface CsvStage {
    data object Idle : CsvStage
    data class Problems(val lines: List<Msg>, val more: Int) : CsvStage
    data class ResolveFlights(
        val imp: FlightImport,
        val fileName: String,
        val firstLogNo: Int,
        val gliderOptions: List<PickerOption>,
        val airfieldOptions: List<PickerOption>,
        /** Labels hold the stored names; the screen shows seeded types in the pilot's language. */
        val launchTypeOptions: List<PickerOption>,
        val gliders: Map<String, String?>,
        val airfields: Map<String, String?>,
        val defaultLaunchTypeId: String?,
        val profileName: String,
    ) : CsvStage
    data class ConfirmRecords(val gliders: Boolean, val imp: RecordImport<*>, val fileName: String) : CsvStage
    data class ConfirmUndo(val preview: UndoPreview) : CsvStage
}

/** The picker option meaning "create a stub record". */
private const val CREATE = ""

class SpreadsheetsViewModel(private val app: SoarlogsApp) : ViewModel() {
    private val csv = app.csv
    var stage by mutableStateOf<CsvStage>(CsvStage.Idle)
        private set
    var message by mutableStateOf<UiText?>(null)
    var busy by mutableStateOf(false)
        private set

    private fun work(block: suspend () -> Unit) {
        busy = true
        message = null
        viewModelScope.launch {
            runCatching { block() }.onFailure { message = uiText(R.string.common_nothing_changed, it.message ?: it.javaClass.simpleName) }
            busy = false
        }
    }

    private fun problems(errors: List<CsvError>) {
        stage = CsvStage.Problems(errors.take(FlightCsv.MAX_ERRORS).map { it.message }, (errors.size - FlightCsv.MAX_ERRORS).coerceAtLeast(0))
    }

    private suspend fun read(uri: Uri): String =
        Documents.readText(app.contentResolver, uri) ?: error(app.getString(R.string.msg_backup_file_unreadable))

    fun export(tree: Uri) = work {
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
        val r = app.contentResolver
        // File names stay in English, like the column names inside them.
        val witness = app.settings.settings.first().witnessSignoff
        Documents.writeToFolder(r, tree, "soarlogs-flights-$stamp.csv", "text/csv", csv.exportFlights(witness))
        Documents.writeToFolder(r, tree, "soarlogs-gliders-$stamp.csv", "text/csv", csv.exportGliders())
        Documents.writeToFolder(r, tree, "soarlogs-airfields-$stamp.csv", "text/csv", csv.exportAirfields())
        message = uiText(R.string.csv_exported, stamp)
    }

    fun importFlights(uri: Uri) = work {
        val imp = csv.prepareFlights(read(uri))
        if (imp.errors.isNotEmpty()) return@work problems(imp.errors)
        val repo = app.repository
        val types = repo.observeLaunchTypes().first().filter { !it.archived }
        stage = CsvStage.ResolveFlights(
            imp = imp,
            fileName = Documents.displayName(app.contentResolver, uri),
            firstLogNo = csv.nextLogNo(),
            gliderOptions = repo.observeGliders().first().map { PickerOption(it.id, it.pickerLabel()) },
            airfieldOptions = repo.observeAirfields().first().map { PickerOption(it.id, it.name) },
            launchTypeOptions = types.map { PickerOption(it.id, it.name) },
            gliders = imp.unmatchedGliders.keys.associateWith { null },
            airfields = imp.unmatchedAirfields.keys.associateWith { null },
            defaultLaunchTypeId = null,
            profileName = app.settings.settings.first().pilotName,
        )
    }

    fun update(s: CsvStage.ResolveFlights) {
        stage = s
    }

    fun commitFlights() {
        val s = stage as? CsvStage.ResolveFlights ?: return
        work {
            val r = csv.commitFlights(s.imp, FlightImportChoices(s.gliders, s.airfields, s.defaultLaunchTypeId, s.profileName), s.fileName)
            stage = CsvStage.Idle
            message = uiText(R.string.csv_imported_flights, r.flights, r.gliders, r.airfields, r.clubs)
        }
    }

    fun importGliders(uri: Uri) = work {
        val imp = csv.prepareGliders(read(uri))
        if (imp.errors.isNotEmpty()) return@work problems(imp.errors)
        stage = CsvStage.ConfirmRecords(gliders = true, imp, Documents.displayName(app.contentResolver, uri))
    }

    fun importAirfields(uri: Uri) = work {
        val imp = csv.prepareAirfields(read(uri))
        if (imp.errors.isNotEmpty()) return@work problems(imp.errors)
        stage = CsvStage.ConfirmRecords(gliders = false, imp, Documents.displayName(app.contentResolver, uri))
    }

    @Suppress("UNCHECKED_CAST")
    fun commitRecords() {
        val s = stage as? CsvStage.ConfirmRecords ?: return
        work {
            message = if (s.gliders) {
                val r = csv.commitGliders(s.imp as RecordImport<ParsedGlider>, s.fileName)
                uiText(R.string.csv_added_gliders, r.gliders)
            } else {
                val r = csv.commitAirfields(s.imp as RecordImport<ParsedAirfield>, s.fileName)
                uiText(R.string.csv_added_airfields, r.airfields, r.clubs)
            }
            stage = CsvStage.Idle
        }
    }

    fun askUndo() = work {
        val p = csv.undoPreview()
        if (p == null) {
            message = uiText(R.string.csv_nothing_to_undo)
        } else {
            stage = CsvStage.ConfirmUndo(p)
        }
    }

    fun undo() {
        val s = stage as? CsvStage.ConfirmUndo ?: return
        work {
            csv.undo(s.preview.batch.id)
            stage = CsvStage.Idle
            message = uiText(R.string.csv_undone, s.preview.batch.fileName)
        }
    }

    fun cancel() {
        stage = CsvStage.Idle
    }
}

private val csvTypes = arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/vnd.ms-excel", "application/octet-stream")

/** Export and import of flights, gliders and airfields as CSV, and Undo last import. */
@Composable
fun SpreadsheetsScreen() {
    val vm: SpreadsheetsViewModel = viewModel(factory = vmFactory { SpreadsheetsViewModel(it) })
    val exportTo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(vm::export) }
    val flights = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importFlights) }
    val gliders = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importGliders) }
    val airfields = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importAirfields) }

    val s = vm.stage
    if (s is CsvStage.ResolveFlights) {
        ResolveFlights(s, vm)
        return
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.csv_intro), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { exportTo.launch(null) }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.csv_export))
        }
        Text(stringResource(R.string.csv_export_hint), style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text(stringResource(R.string.csv_import), style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = { flights.launch(csvTypes) }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.csv_import_flights))
        }
        OutlinedButton(onClick = { gliders.launch(csvTypes) }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.csv_import_gliders))
        }
        OutlinedButton(onClick = { airfields.launch(csvTypes) }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.csv_import_airfields))
        }
        Text(stringResource(R.string.csv_import_hint), style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        OutlinedButton(onClick = vm::askUndo, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.csv_undo))
        }
        vm.message?.let { Text(it.text()) }
    }

    when (s) {
        is CsvStage.Problems -> AlertDialog(
            onDismissRequest = vm::cancel,
            title = { Text(stringResource(R.string.csv_not_imported)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    s.lines.forEach { Text(it.text(), style = MaterialTheme.typography.bodySmall) }
                    if (s.more > 0) Text(stringResource(R.string.backup_more_problems, s.more), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = vm::cancel) { Text(stringResource(R.string.common_ok)) } },
        )
        is CsvStage.ConfirmRecords -> AlertDialog(
            onDismissRequest = vm::cancel,
            title = {
                Text(
                    stringResource(
                        if (s.gliders) R.string.csv_confirm_gliders else R.string.csv_confirm_airfields, s.imp.records.size,
                    ),
                )
            },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.csv_from_file, s.fileName))
                    (s.imp.warnings + s.imp.skipped).forEach { Text(it.text(), style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                TextButton(onClick = vm::commitRecords, enabled = s.imp.records.isNotEmpty()) { Text(stringResource(R.string.csv_import)) }
            },
            dismissButton = { TextButton(onClick = vm::cancel) { Text(stringResource(R.string.common_cancel)) } },
        )
        is CsvStage.ConfirmUndo -> AlertDialog(
            onDismissRequest = vm::cancel,
            title = { Text(stringResource(R.string.csv_undo_title)) },
            text = {
                val p = s.preview
                val date = Instant.ofEpochMilli(p.batch.createdAt).atZone(ZoneId.systemDefault()).toLocalDate()
                Text(stringResource(R.string.csv_undo_text, p.flights, p.gliders, p.airfields, p.batch.fileName, date.shortText()))
            },
            confirmButton = { TextButton(onClick = vm::undo) { Text(stringResource(R.string.csv_remove)) } },
            dismissButton = { TextButton(onClick = vm::cancel) { Text(stringResource(R.string.common_cancel)) } },
        )
        else -> Unit
    }
}

/** The confirmation screen for a flights file: unmatched names, defaults, then Import. */
@Composable
private fun ResolveFlights(s: CsvStage.ResolveFlights, vm: SpreadsheetsViewModel) {
    val imp = s.imp
    val ready = (imp.rowsNeedingLaunchType == 0 || s.defaultLaunchTypeId != null) &&
        (imp.rowsNeedingProfileName == 0 || s.profileName.isNotBlank())

    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.csv_resolve_title, imp.flights.size, s.fileName), style = MaterialTheme.typography.titleMedium)
        imp.warnings.forEach { Text(it.text(), style = MaterialTheme.typography.bodySmall) }

        if (imp.unmatchedGliders.isNotEmpty()) {
            Text(stringResource(R.string.csv_unmatched_gliders), style = MaterialTheme.typography.titleSmall)
            imp.unmatchedGliders.forEach { (key, written) ->
                PickerField(
                    label = written,
                    selectedId = s.gliders[key] ?: CREATE,
                    options = listOf(PickerOption(CREATE, stringResource(R.string.csv_create_stub, written))) + s.gliderOptions,
                    onPick = { id -> vm.update(s.copy(gliders = s.gliders + (key to id.ifEmpty { null }))) },
                )
            }
        }
        if (imp.unmatchedAirfields.isNotEmpty()) {
            Text(stringResource(R.string.csv_unmatched_airfields), style = MaterialTheme.typography.titleSmall)
            imp.unmatchedAirfields.forEach { (key, written) ->
                PickerField(
                    label = written,
                    selectedId = s.airfields[key] ?: CREATE,
                    options = listOf(PickerOption(CREATE, stringResource(R.string.csv_create_stub, written))) + s.airfieldOptions,
                    onPick = { id -> vm.update(s.copy(airfields = s.airfields + (key to id.ifEmpty { null }))) },
                )
            }
        }
        if (imp.rowsNeedingLaunchType > 0) {
            PickerField(
                label = stringResource(R.string.csv_default_launch_type, imp.rowsNeedingLaunchType),
                selectedId = s.defaultLaunchTypeId,
                options = s.launchTypeOptions.map { PickerOption(it.id, launchTypeName(it.id, it.label)) },
                onPick = { vm.update(s.copy(defaultLaunchTypeId = it)) },
            )
        }
        if (imp.needsNumbering) {
            Text(stringResource(R.string.csv_numbering, s.firstLogNo, s.firstLogNo + imp.flights.size - 1))
        }
        if (imp.rowsNeedingProfileName > 0) {
            OutlinedTextField(
                s.profileName, { vm.update(s.copy(profileName = it)) },
                label = { Text(stringResource(R.string.csv_default_pic, imp.rowsNeedingProfileName)) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = vm::cancel, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.common_cancel)) }
            Button(onClick = vm::commitFlights, enabled = ready && !vm.busy, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.csv_import))
            }
        }
        vm.message?.let { Text(it.text(), color = MaterialTheme.colorScheme.error) }
    }
}
