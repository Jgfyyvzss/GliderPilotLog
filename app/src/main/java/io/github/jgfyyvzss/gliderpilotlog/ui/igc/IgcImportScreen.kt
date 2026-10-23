package io.github.jgfyyvzss.gliderpilotlog.ui.igc

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jgfyyvzss.gliderpilotlog.R
import io.github.jgfyyvzss.gliderpilotlog.core.Durations
import io.github.jgfyyvzss.gliderpilotlog.core.MsgKey
import io.github.jgfyyvzss.gliderpilotlog.core.msg
import io.github.jgfyyvzss.gliderpilotlog.data.IgcImportState
import io.github.jgfyyvzss.gliderpilotlog.ui.appContainer
import io.github.jgfyyvzss.gliderpilotlog.ui.text
import java.time.ZoneId

/** Opens the IGC file picker. Any file type is offered, because IGC files have no agreed MIME type. */
@Composable
fun rememberIgcPicker(): () -> Unit {
    val app = appContainer()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(app.igcImport::open) }
    return { launcher.launch(arrayOf("*/*")) }
}

/**
 * The file's flying segments. A file with one segment goes straight to the pre-filled flight form; with several,
 * each is listed with its local launch time and duration and imported as its own flight. The file's WeGlide status
 * is shown with an Upload to WeGlide button, so logging and uploading are each one step from here.
 */
@Composable
fun IgcImportScreen(onImport: (Int) -> Unit, onUpload: () -> Unit, onFinish: () -> Unit) {
    val app = appContainer()
    val state by app.igcImport.state.collectAsStateWithLifecycle()
    val files by app.igcFiles.files.collectAsStateWithLifecycle()
    val pick = rememberIgcPicker()
    var autoOpened by rememberSaveable { mutableStateOf(false) }
    val zone = ZoneId.systemDefault()

    val ready = state as? IgcImportState.Ready
    LaunchedEffect(ready) {
        val r = ready ?: return@LaunchedEffect
        // Stay here once every flight is logged, so the WeGlide status and button are seen.
        if (r.segments.size == 1 && !autoOpened && 0 !in r.imported) {
            autoOpened = true
            onImport(0)
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (val s = state) {
            IgcImportState.Reading -> Text(stringResource(R.string.igc_reading))
            is IgcImportState.Failed, null -> {
                Text(
                    (s?.message ?: msg(MsgKey.IGC_UNREADABLE)).text(),
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedButton(onClick = pick, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.igc_choose_other)) }
            }
            is IgcImportState.Ready -> {
                if (s.segments.size > 1) Text(stringResource(R.string.igc_segments, s.segments.size))
                s.segments.forEachIndexed { i, segment ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.igc_segment, segment.launchTime(zone).toString(), Durations.formatHm(segment.durationMinutes)),
                            Modifier.weight(1f),
                        )
                        if (i in s.imported) {
                            Text(stringResource(R.string.igc_imported), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            OutlinedButton(onClick = { onImport(i) }) { Text(stringResource(R.string.igc_import)) }
                        }
                    }
                }
                if (s.imported.size == s.segments.size) Text(stringResource(R.string.igc_all_logged))
                files.firstOrNull { it.id == s.fileId }?.let { f ->
                    HorizontalDivider()
                    Text(weglideStatus(f))
                    if (!f.uploaded) {
                        OutlinedButton(onClick = { app.uploadSelection.select(f.id); onUpload() }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.upload_button))
                        }
                    }
                }
                Button(onClick = { app.igcImport.clear(); onFinish() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.igc_done))
                }
            }
        }
    }
}
