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
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { app.aircraft.load() }

    ListItem(
        headlineContent = { Text("WeGlide aircraft list") },
        supportingContent = {
            Text(
                state?.let { s ->
                    "${s.aircraft.size} aircraft, " + (s.updatedAt?.let { "updated ${lastBackupText(it)}" } ?: "the copy bundled with the app")
                } ?: "Loading",
            )
        },
        trailingContent = {
            TextButton(onClick = {
                busy = true
                message = null
                scope.launch {
                    message = app.aircraft.update().fold({ "Updated: $it aircraft" }, { "Not updated: ${it.message}" })
                    busy = false
                }
            }, enabled = !busy) { Text(if (busy) "Updating" else "Update") }
        },
    )
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
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
    Text("WeGlide type", style = MaterialTheme.typography.titleMedium)
    Text(
        when {
            g.weglideAircraftId == null -> "Not set. WeGlide upload needs it, so WeGlide does not have to guess the variant."
            current == null -> "WeGlide aircraft ${g.weglideAircraftId}, not in the current list"
            else -> current.label
        },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = { choosing = true }, modifier = Modifier.weight(1f)) { Text("Choose") }
        if (g.weglideAircraftId != null) {
            OutlinedButton(onClick = { scope.launch { app.repository.setWeGlideAircraft(gliderId, null) } }, modifier = Modifier.weight(1f)) {
                Text("Clear")
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

/** Search WeGlide's list, then confirm the type change before saving. */
@Composable
private fun AircraftPicker(
    aircraft: List<WeGlideAircraft>,
    initialQuery: String,
    currentType: String,
    onDismiss: () -> Unit,
    onPick: (WeGlideAircraft) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var confirming by remember { mutableStateOf<WeGlideAircraft?>(null) }
    val results = remember(query, aircraft) { AircraftSearch.search(aircraft, query) }

    val pick = confirming
    if (pick != null) {
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(pick.label) },
            text = {
                Text(
                    if (currentType == pick.name) "The type stays ${pick.name}."
                    else "Type will change from ${currentType.ifBlank { "(blank)" }} to ${pick.name}. The old text is not kept, " +
                        "but the type stays editable by hand.",
                )
            },
            confirmButton = { TextButton(onClick = { onPick(pick) }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("Back") } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("WeGlide aircraft") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it }, label = { Text("Search") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (results.isEmpty()) Text("No match. Try fewer letters, for example ASK.")
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(results, key = { it.id }) { a ->
                        ListItem(headlineContent = { Text(a.label) }, modifier = Modifier.clickable { confirming = a })
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
