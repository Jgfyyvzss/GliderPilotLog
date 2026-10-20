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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.jgfyyvzss.gliderpilotlog.SoarlogsApp
import io.github.jgfyyvzss.gliderpilotlog.core.csv.CsvError
import io.github.jgfyyvzss.gliderpilotlog.core.csv.FlightCsv
import io.github.jgfyyvzss.gliderpilotlog.core.csv.FlightImport
import io.github.jgfyyvzss.gliderpilotlog.core.csv.RecordImport
import io.github.jgfyyvzss.gliderpilotlog.data.Documents
import io.github.jgfyyvzss.gliderpilotlog.data.FlightImportChoices
import io.github.jgfyyvzss.gliderpilotlog.data.UndoPreview
import io.github.jgfyyvzss.gliderpilotlog.ui.PickerField
import io.github.jgfyyvzss.gliderpilotlog.ui.PickerOption
import io.github.jgfyyvzss.gliderpilotlog.ui.gliders.pickerLabel
import io.github.jgfyyvzss.gliderpilotlog.ui.isoDateShort
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
    data class Problems(val title: String, val lines: List<String>, val more: Int) : CsvStage
    data class ResolveFlights(
        val imp: FlightImport,
        val fileName: String,
        val firstLogNo: Int,
        val gliderOptions: List<PickerOption>,
        val airfieldOptions: List<PickerOption>,
        val launchTypeOptions: List<PickerOption>,
        val gliders: Map<String, String?>,
        val airfields: Map<String, String?>,
        val defaultLaunchTypeId: String?,
        val profileName: String,
    ) : CsvStage
    data class ConfirmRecords(val kind: String, val imp: RecordImport<*>, val fileName: String) : CsvStage
    data class ConfirmUndo(val preview: UndoPreview) : CsvStage
}

/** The picker option meaning "create a stub record". */
private const val CREATE = ""

class SpreadsheetsViewModel(private val app: SoarlogsApp) : ViewModel() {
    private val csv = app.csv
    var stage by mutableStateOf<CsvStage>(CsvStage.Idle)
        private set
    var message by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
        private set

    private fun work(block: suspend () -> Unit) {
        busy = true
        message = null
        viewModelScope.launch {
            runCatching { block() }.onFailure { message = "Nothing was changed: ${it.message}" }
            busy = false
        }
    }

    private fun problems(title: String, errors: List<CsvError>) {
        stage = CsvStage.Problems(title, errors.take(FlightCsv.MAX_ERRORS).map { it.message }, (errors.size - FlightCsv.MAX_ERRORS).coerceAtLeast(0))
    }

    fun export(tree: Uri) = work {
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
        val r = app.contentResolver
        Documents.writeToFolder(r, tree, "soarlogs-flights-$stamp.csv", "text/csv", csv.exportFlights())
        Documents.writeToFolder(r, tree, "soarlogs-gliders-$stamp.csv", "text/csv", csv.exportGliders())
        Documents.writeToFolder(r, tree, "soarlogs-airfields-$stamp.csv", "text/csv", csv.exportAirfields())
        message = "Exported flights, gliders and airfields ($stamp)"
    }

    fun importFlights(uri: Uri) = work {
        val text = Documents.readText(app.contentResolver, uri) ?: error("the file could not be read")
        val imp = csv.prepareFlights(text)
        if (imp.errors.isNotEmpty()) return@work problems("The file was not imported", imp.errors)
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
            message = "Imported ${r.flights} flights" + created(r.gliders, r.airfields, r.clubs)
        }
    }

    fun importGliders(uri: Uri) = work {
        val text = Documents.readText(app.contentResolver, uri) ?: error("the file could not be read")
        val imp = csv.prepareGliders(text)
        if (imp.errors.isNotEmpty()) return@work problems("The file was not imported", imp.errors)
        stage = CsvStage.ConfirmRecords("gliders", imp, Documents.displayName(app.contentResolver, uri))
    }

    fun importAirfields(uri: Uri) = work {
        val text = Documents.readText(app.contentResolver, uri) ?: error("the file could not be read")
        val imp = csv.prepareAirfields(text)
        if (imp.errors.isNotEmpty()) return@work problems("The file was not imported", imp.errors)
        stage = CsvStage.ConfirmRecords("airfields", imp, Documents.displayName(app.contentResolver, uri))
    }

    @Suppress("UNCHECKED_CAST")
    fun commitRecords() {
        val s = stage as? CsvStage.ConfirmRecords ?: return
        work {
            val r = if (s.kind == "gliders") {
                csv.commitGliders(s.imp as RecordImport<io.github.jgfyyvzss.gliderpilotlog.core.csv.ParsedGlider>, s.fileName)
            } else {
                csv.commitAirfields(s.imp as RecordImport<io.github.jgfyyvzss.gliderpilotlog.core.csv.ParsedAirfield>, s.fileName)
            }
            stage = CsvStage.Idle
            message = if (s.kind == "gliders") "Added ${r.gliders} gliders" else "Added ${r.airfields} airfields" + created(0, 0, r.clubs)
        }
    }

    fun askUndo() = work {
        val p = csv.undoPreview()
        if (p == null) {
            message = "There is no import to undo"
        } else {
            stage = CsvStage.ConfirmUndo(p)
        }
    }

    fun undo() {
        val s = stage as? CsvStage.ConfirmUndo ?: return
        work {
            csv.undo(s.preview.batch.id)
            stage = CsvStage.Idle
            message = "Removed the import of ${s.preview.batch.fileName}"
        }
    }

    fun cancel() {
        stage = CsvStage.Idle
    }

    private fun created(gliders: Int, airfields: Int, clubs: Int): String {
        val parts = listOfNotNull(
            gliders.takeIf { it > 0 }?.let { "$it new gliders" },
            airfields.takeIf { it > 0 }?.let { "$it new airfields" },
            clubs.takeIf { it > 0 }?.let { "$it new clubs" },
        )
        return if (parts.isEmpty()) "" else ", with " + parts.joinToString(", ")
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
        Text("CSV files are for spreadsheets and other systems. Use Backup in Settings to keep your logbook safe.",
            style = MaterialTheme.typography.bodySmall)
        Button(onClick = { exportTo.launch(null) }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("Export to a folder") }
        Text("Writes flights, gliders and airfields as three CSV files.", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text("Import", style = MaterialTheme.typography.titleMedium)
        FilledTonalButton(onClick = { flights.launch(csvTypes) }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("Import flights") }
        FilledTonalButton(onClick = { gliders.launch(csvTypes) }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("Import gliders") }
        FilledTonalButton(onClick = { airfields.launch(csvTypes) }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("Import airfields") }
        Text(
            "The whole file is checked first and nothing is imported if any row has an error. Gliders and airfields that " +
                "already exist are skipped, never updated.",
            style = MaterialTheme.typography.bodySmall,
        )
        HorizontalDivider()
        OutlinedButton(onClick = vm::askUndo, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("Undo last import") }
        vm.message?.let { Text(it) }
    }

    when (s) {
        is CsvStage.Problems -> AlertDialog(
            onDismissRequest = vm::cancel,
            title = { Text(s.title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    s.lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (s.more > 0) Text("and ${s.more} more", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = vm::cancel) { Text("OK") } },
        )
        is CsvStage.ConfirmRecords -> AlertDialog(
            onDismissRequest = vm::cancel,
            title = { Text("Import ${s.imp.records.size} ${s.kind}?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("From ${s.fileName}.")
                    (s.imp.warnings + s.imp.skipped).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = { TextButton(onClick = vm::commitRecords, enabled = s.imp.records.isNotEmpty()) { Text("Import") } },
            dismissButton = { TextButton(onClick = vm::cancel) { Text("Cancel") } },
        )
        is CsvStage.ConfirmUndo -> AlertDialog(
            onDismissRequest = vm::cancel,
            title = { Text("Undo the last import?") },
            text = {
                val p = s.preview
                val date = Instant.ofEpochMilli(p.batch.createdAt).atZone(ZoneId.systemDefault()).toLocalDate().toString()
                Text(
                    "Remove ${p.flights} flights, ${p.gliders} gliders and ${p.airfields} airfields added by the import of " +
                        "${p.batch.fileName} on ${isoDateShort(date)}? Gliders and airfields still used by other flights stay. " +
                        "Flights from this import are removed even if you have edited them since.",
                )
            },
            confirmButton = { TextButton(onClick = vm::undo) { Text("Remove") } },
            dismissButton = { TextButton(onClick = vm::cancel) { Text("Cancel") } },
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
        Text("Import ${imp.flights.size} flights from ${s.fileName}", style = MaterialTheme.typography.titleMedium)
        imp.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }

        if (imp.unmatchedGliders.isNotEmpty()) {
            Text("Gliders not in your list", style = MaterialTheme.typography.titleSmall)
            imp.unmatchedGliders.forEach { (key, written) ->
                PickerField(
                    label = written,
                    selectedId = s.gliders[key] ?: CREATE,
                    options = listOf(PickerOption(CREATE, "Create $written (to complete later)")) + s.gliderOptions,
                    onPick = { id -> vm.update(s.copy(gliders = s.gliders + (key to id.ifEmpty { null }))) },
                )
            }
        }
        if (imp.unmatchedAirfields.isNotEmpty()) {
            Text("Airfields not in your list", style = MaterialTheme.typography.titleSmall)
            imp.unmatchedAirfields.forEach { (key, written) ->
                PickerField(
                    label = written,
                    selectedId = s.airfields[key] ?: CREATE,
                    options = listOf(PickerOption(CREATE, "Create $written (to complete later)")) + s.airfieldOptions,
                    onPick = { id -> vm.update(s.copy(airfields = s.airfields + (key to id.ifEmpty { null }))) },
                )
            }
        }
        if (imp.rowsNeedingLaunchType > 0) {
            PickerField(
                label = "Launch type for ${imp.rowsNeedingLaunchType} rows without one",
                selectedId = s.defaultLaunchTypeId,
                options = s.launchTypeOptions,
                onPick = { vm.update(s.copy(defaultLaunchTypeId = it)) },
            )
        }
        if (imp.needsNumbering) {
            Text("The file has no log_no column. Flights will be numbered ${s.firstLogNo} to ${s.firstLogNo + imp.flights.size - 1} in file order.")
        }
        if (imp.rowsNeedingProfileName > 0) {
            OutlinedTextField(
                s.profileName, { vm.update(s.copy(profileName = it)) },
                label = { Text("Pilot in command for ${imp.rowsNeedingProfileName} PIC, Instructor and Coach rows without one") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = vm::cancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
            Button(onClick = vm::commitFlights, enabled = ready && !vm.busy, modifier = Modifier.weight(1f)) { Text("Import") }
        }
        vm.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
