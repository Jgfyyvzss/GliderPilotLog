package io.github.jgfyyvzss.gliderpilotlog.ui.gliders

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.jgfyyvzss.gliderpilotlog.data.GliderEntity
import io.github.jgfyyvzss.gliderpilotlog.data.LogbookRepository
import io.github.jgfyyvzss.gliderpilotlog.data.SaveResult
import io.github.jgfyyvzss.gliderpilotlog.ui.Badge
import io.github.jgfyyvzss.gliderpilotlog.ui.appContainer
import io.github.jgfyyvzss.gliderpilotlog.ui.vmFactory
import kotlinx.coroutines.launch

@Composable
fun GliderListScreen(onOpen: (String) -> Unit, onNew: () -> Unit) {
    val gliders by appContainer().repository.observeGliders().collectAsStateWithLifecycle(emptyList())
    Box(Modifier.fillMaxSize()) {
        if (gliders.isEmpty()) {
            Text("No gliders yet. Tap + to add one.", Modifier.padding(16.dp))
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(gliders, key = { it.id }) { g ->
                ListItem(
                    headlineContent = { Text(g.registration) },
                    supportingContent = { if (g.type.isNotEmpty()) Text(g.type) },
                    trailingContent = {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (g.incomplete) Badge("Incomplete")
                            if (g.archived) Badge("Archived")
                        }
                    },
                    modifier = Modifier.clickable { onOpen(g.id) },
                )
                HorizontalDivider()
            }
        }
        FloatingActionButton(onClick = onNew, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            Icon(Icons.Default.Add, contentDescription = "Add glider")
        }
    }
}

class GliderEditViewModel(private val repo: LogbookRepository, private val id: String?) : ViewModel() {
    var registration by mutableStateOf("")
    var type by mutableStateOf("")
    var archived by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var loaded by mutableStateOf(id == null)
        private set
    var done by mutableStateOf(false)
        private set

    init {
        if (id != null) viewModelScope.launch {
            repo.glider(id)?.let { registration = it.registration; type = it.type; archived = it.archived }
            loaded = true
        }
    }

    fun save() = viewModelScope.launch {
        when (val r = repo.saveGlider(id, registration, type, archived)) {
            is SaveResult.Saved -> done = true
            is SaveResult.Rejected -> error = r.message
        }
    }
}

@Composable
fun GliderEditScreen(id: String?, onDone: () -> Unit) {
    val vm: GliderEditViewModel = viewModel(factory = vmFactory { GliderEditViewModel(it.repository, id) })
    LaunchedEffect(vm.done) { if (vm.done) onDone() }
    if (!vm.loaded) return
    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = vm.registration,
            onValueChange = { vm.registration = it; vm.error = null },
            label = { Text("Registration") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = vm.type,
            onValueChange = { vm.type = it },
            label = { Text("Type, as the logbook shows it") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (id != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Archived", Modifier.weight(1f))
                Switch(checked = vm.archived, onCheckedChange = { vm.archived = it })
            }
            Text("Archived gliders are hidden from the flight form.", style = MaterialTheme.typography.bodySmall)
        }
        vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = { vm.save() }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
    }
}

/** Glider rows in dropdowns read like `FQM, Hornet`. */
fun GliderEntity.pickerLabel(): String = if (type.isBlank()) registration else "$registration, $type"
