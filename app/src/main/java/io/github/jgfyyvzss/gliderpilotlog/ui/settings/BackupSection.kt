package io.github.jgfyyvzss.gliderpilotlog.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Surface
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.jgfyyvzss.gliderpilotlog.SoarlogsApp
import io.github.jgfyyvzss.gliderpilotlog.core.backup.BackupTime
import io.github.jgfyyvzss.gliderpilotlog.data.BackupOutcome
import io.github.jgfyyvzss.gliderpilotlog.data.Prepared
import io.github.jgfyyvzss.gliderpilotlog.data.Settings
import io.github.jgfyyvzss.gliderpilotlog.ui.appContainer
import io.github.jgfyyvzss.gliderpilotlog.ui.vmFactory
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Backup folder, schedule, Back up now, Restore and Merge. Also drives the first-run Restore. */
class BackupViewModel(private val app: SoarlogsApp) : ViewModel() {
    val settings = app.settings.settings
    var folderName by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
        private set
    /** A checked file waiting for the pilot to confirm, or the reasons it was refused. */
    var pending by mutableStateOf<Prepared?>(null)
        private set
    var restored by mutableStateOf(false)
        private set

    fun loadFolderName(uri: String?) = viewModelScope.launch { folderName = app.backupFolder.folderName(uri) }

    fun chooseFolder(tree: Uri) = viewModelScope.launch {
        runCatching { app.backupFolder.choose(tree) }
            .onSuccess { message = "Backup folder set" }
            .onFailure { message = "That folder cannot be used for backups" }
    }

    fun setSchedule(afterChanges: Int, afterDays: Int) = viewModelScope.launch { app.settings.setBackupSchedule(afterChanges, afterDays) }

    fun backUpNow() = run {
        busy = true
        viewModelScope.launch {
            message = when (val r = app.backupFolder.backUpNow()) {
                is BackupOutcome.Written -> "Backed up to ${r.fileName}"
                BackupOutcome.NoFolder -> "Choose a backup folder first"
                is BackupOutcome.Failed -> "Backup failed: ${r.message}"
            }
            busy = false
        }
    }

    fun openForRestore(uri: Uri) = open(uri) { app.backup.prepareRestore(it) }
    fun openForMerge(uri: Uri) = open(uri) { app.backup.prepareMerge(it) }

    private fun open(uri: Uri, prepare: suspend (String) -> Prepared) = run {
        busy = true
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { app.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull()
            }
            pending = if (text == null) Prepared.Refused(listOf("The file could not be read.")) else prepare(text)
            busy = false
        }
    }

    fun confirm() {
        val p = pending ?: return
        pending = null
        busy = true
        viewModelScope.launch {
            runCatching {
                when (p) {
                    is Prepared.Restore -> { app.backup.restore(p); app.settings.setFirstRunDone(); restored = true; "Logbook restored" }
                    is Prepared.Merge -> { app.backup.merge(p); "Backup merged" }
                    is Prepared.Refused -> null
                }
            }.onSuccess { message = it }.onFailure { message = "Nothing was changed: ${it.message}" }
            busy = false
        }
    }

    fun dismiss() {
        pending = null
    }
}

@Composable
fun backupViewModel(): BackupViewModel = viewModel(factory = vmFactory { BackupViewModel(it) })

@Composable
fun BackupSection(vm: BackupViewModel = backupViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle(Settings())
    LaunchedEffect(settings.backupFolderUri) { vm.loadFolderName(settings.backupFolderUri) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(vm::chooseFolder) }
    val pickRestore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::openForRestore) }
    val pickMerge = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::openForMerge) }
    val jsonTypes = arrayOf("application/json", "application/octet-stream", "text/plain")

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Backup", style = MaterialTheme.typography.titleMedium)
        ListItem(
            headlineContent = { Text("Backup folder") },
            supportingContent = {
                Text(
                    when {
                        settings.backupFolderUri == null -> "Not set. Tap to choose a folder, which can be a cloud-synced one."
                        vm.folderName == null -> "The chosen folder can no longer be reached. Tap to choose again."
                        else -> vm.folderName!!
                    },
                )
            },
            modifier = Modifier.clickable { pickFolder.launch(null) },
        )
        Text("Last backup: ${lastBackupText(settings.lastBackupAt)}", style = MaterialTheme.typography.bodyMedium)

        Text("Back up automatically after", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            NumberField("Changes", settings.backupAfterChanges, Modifier.weight(1f)) { vm.setSchedule(it, settings.backupAfterDays) }
            NumberField("Days", settings.backupAfterDays, Modifier.weight(1f)) { vm.setSchedule(settings.backupAfterChanges, it) }
        }
        Text(
            if (settings.backupAfterChanges == 0 && settings.backupAfterDays == 0) "Automatic backups are off."
            else "Whichever comes first, once something has changed. 0 turns a trigger off. The last 10 backups are kept.",
            style = MaterialTheme.typography.bodySmall,
        )

        Button(onClick = vm::backUpNow, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("Back up now") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pickRestore.launch(jsonTypes) }, enabled = !vm.busy, modifier = Modifier.weight(1f)) { Text("Restore") }
            OutlinedButton(onClick = { pickMerge.launch(jsonTypes) }, enabled = !vm.busy, modifier = Modifier.weight(1f)) { Text("Merge") }
        }
        Text(
            "Restore loads a backup into an empty logbook, settings and profile included. Merge combines a backup with " +
                "this phone's logbook, keeping the newer copy of each record, and leaves settings as they are.",
            style = MaterialTheme.typography.bodySmall,
        )
        vm.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }

    PreparedDialog(vm)
}

@Composable
private fun NumberField(label: String, value: Int, modifier: Modifier, onChange: (Int) -> Unit) {
    var text by rememberSaveable(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        text,
        { t ->
            text = t.filter(Char::isDigit).take(4)
            text.toIntOrNull()?.let(onChange)
        },
        label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

/** Confirms a Restore or Merge, or lists why a file was refused. */
@Composable
fun PreparedDialog(vm: BackupViewModel) {
    when (val p = vm.pending) {
        null -> Unit
        is Prepared.Refused -> AlertDialog(
            onDismissRequest = vm::dismiss,
            title = { Text("Nothing was changed") },
            text = {
                LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(p.problems.take(200)) { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (p.problems.size > 200) item { Text("and ${p.problems.size - 200} more") }
                }
            },
            confirmButton = { TextButton(onClick = vm::dismiss) { Text("OK") } },
        )
        is Prepared.Restore -> AlertDialog(
            onDismissRequest = vm::dismiss,
            title = { Text("Restore this backup?") },
            text = {
                Text(
                    "Backup from ${isoTimeText(p.contents.exportedAt)}: ${p.contents.flights} flights, ${p.contents.gliders} gliders, " +
                        "${p.contents.airfields} airfields and ${p.contents.clubs} clubs. Your settings and pilot profile will be " +
                        "replaced by the backup's.",
                )
            },
            confirmButton = { TextButton(onClick = vm::confirm) { Text("Restore") } },
            dismissButton = { TextButton(onClick = vm::dismiss) { Text("Cancel") } },
        )
        is Prepared.Merge -> AlertDialog(
            onDismissRequest = vm::dismiss,
            title = { Text("Merge this backup?") },
            text = {
                Text(
                    "Backup from ${isoTimeText(p.contents.exportedAt)} with ${p.contents.flights} flights. " +
                        "${p.summary.added} records will be added and ${p.summary.updated} updated with a newer copy; " +
                        "${p.summary.unchanged} are already up to date. Settings stay as they are.",
                )
            },
            confirmButton = { TextButton(onClick = vm::confirm) { Text("Merge") } },
            dismissButton = { TextButton(onClick = vm::dismiss) { Text("Cancel") } },
        )
    }
}

/** Shown on an empty logbook the first time the app opens. */
@Composable
fun FirstRunScreen(onDone: () -> Unit) {
    val vm = backupViewModel()
    val pickRestore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::openForRestore) }
    val scope = rememberCoroutineScope()
    val container = appContainer()
    LaunchedEffect(vm.restored) { if (vm.restored) onDone() }

    // This screen is shown outside the main Scaffold, so the Surface supplies the themed background and text colour.
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Text("Welcome to soarlogs", style = MaterialTheme.typography.headlineSmall)
            Text("Start a new logbook, or restore one from a backup file.")
            Button(onClick = {
                scope.launch { container.settings.setFirstRunDone(); onDone() }
            }, modifier = Modifier.fillMaxWidth()) { Text("Start fresh") }
            FilledTonalButton(
                onClick = { pickRestore.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) },
                enabled = !vm.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Restore from backup") }
            vm.message?.let { Text(it) }
        }
    }
    PreparedDialog(vm)
}

fun lastBackupText(at: Long?): String =
    at?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)) }
        ?: "never"

private fun isoTimeText(iso: String): String = BackupTime.fromIso(iso)?.let(::lastBackupText) ?: "an unknown date"
