package io.github.jgfyyvzss.gliderpilotlog.ui.weglide

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.jgfyyvzss.gliderpilotlog.R
import io.github.jgfyyvzss.gliderpilotlog.data.weglide.AircraftUpdate
import io.github.jgfyyvzss.gliderpilotlog.ui.UiText
import io.github.jgfyyvzss.gliderpilotlog.ui.text
import io.github.jgfyyvzss.gliderpilotlog.ui.uiText
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jgfyyvzss.gliderpilotlog.core.weglide.AircraftSearch
import io.github.jgfyyvzss.gliderpilotlog.core.weglide.WeGlideAircraft
import io.github.jgfyyvzss.gliderpilotlog.data.SaveResult
import io.github.jgfyyvzss.gliderpilotlog.ui.appContainer
import io.github.jgfyyvzss.gliderpilotlog.ui.settings.lastBackupText
import kotlinx.coroutines.launch

/** Settings: the aircraft list in use, and a button to update it from WeGlide. */
@Composable
fun AircraftListSection() {
    val app = appContainer()
    val state by app.aircraft.list.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<UiText?>(null) }
    LaunchedEffect(Unit) { app.aircraft.load() }

    ListItem(
        headlineContent = { Text(stringResource(R.string.weglide_list)) },
        supportingContent = {
            Text(
                state?.let { s ->
                    s.updatedAt?.let { stringResource(R.string.weglide_list_updated, s.aircraft.size, lastBackupText(it)) }
                        ?: stringResource(R.string.weglide_list_bundled, s.aircraft.size)
                } ?: stringResource(R.string.weglide_loading),
            )
        },
    )
    Button(
        onClick = {
            busy = true
            message = null
            scope.launch {
                message = when (val r = app.aircraft.update()) {
                    is AircraftUpdate.Updated -> uiText(R.string.weglide_updated, r.count)
                    is AircraftUpdate.Failed -> uiText(R.string.weglide_not_updated, r.message)
                }
                busy = false
            }
        },
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(if (busy) R.string.weglide_updating else R.string.weglide_update)) }
    message?.let { Text(it.text(), style = MaterialTheme.typography.bodySmall) }
}

/**
 * Settings: whether the WeGlide ID and date of birth are saved, with a way to remove them. They are entered on the
 * upload screen, the only place they are used, and are never shown back in full.
 */
@Composable
fun WeGlideAccountSection() {
    val store = appContainer().weglideCredentials
    val saved by store.saved.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { store.load() }
    val isSaved = saved ?: return
    Text(stringResource(R.string.settings_weglide_account))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(if (isSaved) R.string.settings_weglide_account_saved else R.string.settings_weglide_account_none),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        if (isSaved) TextButton(onClick = { scope.launch { store.clear() } }) { Text(stringResource(R.string.settings_weglide_account_clear)) }
    }
}

/**
 * The glider's WeGlide aircraft. Choosing one also replaces the glider's type with WeGlide's name, after showing
 * the change. [onTypeChanged] keeps the glider form's type field in step.
 */
@Composable
fun WeGlideTypeSection(gliderId: String, onTypeChanged: (String) -> Unit) {
    val app = appContainer()
    val glider by remember { app.repository.observeGlider(gliderId) }.collectAsStateWithLifecycle(null)
    val state by app.aircraft.list.collectAsStateWithLifecycle()
    var choosing by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { app.aircraft.load() }
    val g = glider ?: return
    val current = g.weglideAircraftId?.let { id -> state?.aircraft?.firstOrNull { it.id == id } }

    HorizontalDivider()
    Text(stringResource(R.string.weglide_type), style = MaterialTheme.typography.titleMedium)
    Text(
        when {
            g.weglideAircraftId == null -> stringResource(R.string.weglide_type_not_set)
            current == null -> stringResource(R.string.weglide_type_unknown, g.weglideAircraftId.toString())
            else -> current.label
        },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { choosing = true }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.common_choose)) }
        if (g.weglideAircraftId != null) {
            Button(onClick = { scope.launch { app.repository.setWeGlideAircraft(gliderId, null) } }, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.common_clear))
            }
        }
    }

    if (choosing) {
        AircraftPicker(
            aircraft = state?.aircraft.orEmpty(),
            initialQuery = g.type,
            currentType = g.type,
            onDismiss = { choosing = false },
        ) { picked ->
            scope.launch {
                if (app.repository.setWeGlideAircraft(gliderId, picked) is SaveResult.Saved) onTypeChanged(picked.name)
                choosing = false
            }
        }
    }
}

/**
 * Search WeGlide's list, then confirm the type change before saving. Without a glider to change, as for an upload
 * of a file that matches no logbook glider, [currentType] is null and the pick is used straight away.
 */
@Composable
internal fun AircraftPicker(
    aircraft: List<WeGlideAircraft>,
    initialQuery: String,
    currentType: String?,
    onDismiss: () -> Unit,
    onPick: (WeGlideAircraft) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var confirming by remember { mutableStateOf<WeGlideAircraft?>(null) }
    val results = remember(query, aircraft) { AircraftSearch.search(aircraft, query) }

    val pick = confirming
    if (pick != null && currentType != null) {
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(pick.label) },
            text = {
                Text(
                    if (currentType == pick.name) stringResource(R.string.weglide_type_stays, pick.name)
                    else stringResource(R.string.weglide_type_changes, currentType.ifBlank { stringResource(R.string.weglide_blank) }, pick.name),
                )
            },
            confirmButton = { TextButton(onClick = { onPick(pick) }) { Text(stringResource(R.string.common_save)) } },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text(stringResource(R.string.common_back)) } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.weglide_aircraft)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.weglide_search)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (results.isEmpty()) Text(stringResource(R.string.weglide_no_match))
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(results, key = { it.id }) { a ->
                        ListItem(headlineContent = { Text(a.label) }, modifier = Modifier.clickable { if (currentType == null) onPick(a) else confirming = a })
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
