package io.github.jgfyyvzss.gliderpilotlog.ui.airfields

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
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
import io.github.jgfyyvzss.gliderpilotlog.data.ClubEntity
import io.github.jgfyyvzss.gliderpilotlog.data.LogbookRepository
import io.github.jgfyyvzss.gliderpilotlog.data.SaveResult
import io.github.jgfyyvzss.gliderpilotlog.ui.Badge
import io.github.jgfyyvzss.gliderpilotlog.ui.PickerField
import io.github.jgfyyvzss.gliderpilotlog.ui.PickerOption
import io.github.jgfyyvzss.gliderpilotlog.ui.appContainer
import io.github.jgfyyvzss.gliderpilotlog.ui.vmFactory
import io.github.jgfyyvzss.gliderpilotlog.core.Keys
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** The default IGC match radius, from Appendix D. */
const val DEFAULT_MATCH_RADIUS_M = 1000

@Composable
fun AirfieldListScreen(onOpen: (String) -> Unit, onNew: () -> Unit) {
    val rows by appContainer().repository.observeAirfieldList().collectAsStateWithLifecycle(emptyList())
    Box(Modifier.fillMaxSize()) {
        if (rows.isEmpty()) Text("No airfields yet. Tap + to add one.", Modifier.padding(16.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(rows, key = { it.id }) { a ->
                ListItem(
                    headlineContent = { Text(a.name) },
                    supportingContent = { a.clubNames?.let { Text(it) } },
                    trailingContent = {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (a.incomplete) Badge("Incomplete")
                            if (a.archived) Badge("Archived")
                        }
                    },
                    modifier = Modifier.clickable { onOpen(a.id) },
                )
                HorizontalDivider()
            }
        }
        FloatingActionButton(onClick = onNew, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            Icon(Icons.Default.Add, contentDescription = "Add airfield")
        }
    }
}

class AirfieldEditViewModel(private val repo: LogbookRepository, private val id: String?) : ViewModel() {
    var name by mutableStateOf("")
    var latitude by mutableStateOf("")
    var longitude by mutableStateOf("")
    var elevation by mutableStateOf("")
    var radius by mutableStateOf("")
    var archived by mutableStateOf(false)
    val clubs = mutableStateListOf<ClubEntity>()
    var error by mutableStateOf<String?>(null)
    var loaded by mutableStateOf(id == null)
        private set
    var done by mutableStateOf(false)
        private set

    val allClubs = repo.observeClubs()

    init {
        if (id != null) viewModelScope.launch {
            repo.airfield(id)?.let { a ->
                name = a.name
                latitude = a.latitude?.toString() ?: ""
                longitude = a.longitude?.toString() ?: ""
                elevation = a.elevationM?.toString() ?: ""
                radius = a.matchRadiusM?.toString() ?: ""
                archived = a.archived
                clubs.addAll(repo.clubsFor(id))
            }
            loaded = true
        }
    }

    fun addClub(club: ClubEntity) {
        if (clubs.none { it.id == club.id }) clubs.add(club)
    }

    /** Creates a club by name, or reuses the existing one with that name, and links it. */
    fun addNewClub(clubName: String) = viewModelScope.launch {
        when (val r = repo.saveClub(null, clubName)) {
            is SaveResult.Saved -> repo.observeClubsOnce().firstOrNull { it.id == r.id }?.let(::addClub)
            is SaveResult.Rejected -> {
                val existing = repo.observeClubsOnce().firstOrNull {
                    Keys.nameKey(it.name) == Keys.nameKey(clubName)
                }
                if (existing != null) addClub(existing) else error = r.message
            }
        }
    }

    fun save() = viewModelScope.launch {
        fun parseDouble(s: String) = s.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
        fun parseInt(s: String) = s.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
        if (latitude.isNotBlank() && parseDouble(latitude) == null) { error = "Latitude must be decimal degrees"; return@launch }
        if (longitude.isNotBlank() && parseDouble(longitude) == null) { error = "Longitude must be decimal degrees"; return@launch }
        if (elevation.isNotBlank() && parseInt(elevation) == null) { error = "Elevation must be whole metres"; return@launch }
        if (radius.isNotBlank() && parseInt(radius) == null) { error = "Match radius must be whole metres"; return@launch }
        val r = repo.saveAirfield(
            id, name, parseDouble(latitude), parseDouble(longitude), parseInt(elevation), parseInt(radius),
            clubs.map { it.id }, archived,
        )
        when (r) {
            is SaveResult.Saved -> done = true
            is SaveResult.Rejected -> error = r.message
        }
    }
}

private suspend fun LogbookRepository.observeClubsOnce(): List<ClubEntity> =
    observeClubs().first()

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AirfieldEditScreen(id: String?, onDone: () -> Unit) {
    val vm: AirfieldEditViewModel = viewModel(factory = vmFactory { AirfieldEditViewModel(it.repository, id) })
    LaunchedEffect(vm.done) { if (vm.done) onDone() }
    val allClubs by vm.allClubs.collectAsStateWithLifecycle(emptyList())
    var newClubOpen by rememberSaveable { mutableStateOf(false) }
    if (!vm.loaded) return

    val decimal = KeyboardOptions(keyboardType = KeyboardType.Text)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(vm.name, { vm.name = it; vm.error = null }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(vm.latitude, { vm.latitude = it }, label = { Text("Latitude") }, singleLine = true,
                keyboardOptions = decimal, modifier = Modifier.weight(1f))
            OutlinedTextField(vm.longitude, { vm.longitude = it }, label = { Text("Longitude") }, singleLine = true,
                keyboardOptions = decimal, modifier = Modifier.weight(1f))
        }
        Text("Decimal degrees, for example -36.7314. Needed for IGC matching.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(vm.elevation, { vm.elevation = it }, label = { Text("Elevation (m)") }, singleLine = true,
                keyboardOptions = decimal, modifier = Modifier.weight(1f))
            OutlinedTextField(vm.radius, { vm.radius = it }, label = { Text("Match radius (m)") }, singleLine = true,
                placeholder = { Text("$DEFAULT_MATCH_RADIUS_M") }, keyboardOptions = decimal, modifier = Modifier.weight(1f))
        }

        Text("Clubs", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            vm.clubs.forEach { c ->
                InputChip(
                    selected = false,
                    onClick = { vm.clubs.remove(c) },
                    label = { Text(c.name) },
                    trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Remove ${c.name}") },
                )
            }
        }
        val available = allClubs.filter { c -> vm.clubs.none { it.id == c.id } }
        PickerField(
            label = "Add an existing club",
            selectedId = null,
            options = available.map { PickerOption(it.id, it.name) },
            onPick = { picked -> available.firstOrNull { it.id == picked }?.let(vm::addClub) },
        )
        OutlinedButton(onClick = { newClubOpen = true }) { Text("New club") }

        if (id != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Archived", Modifier.weight(1f))
                Switch(checked = vm.archived, onCheckedChange = { vm.archived = it })
            }
        }
        vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = { vm.save() }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
    }

    if (newClubOpen) {
        NameDialog(title = "New club", initial = "", onDismiss = { newClubOpen = false }) {
            vm.addNewClub(it)
            newClubOpen = false
        }
    }
}

/** A dialog asking for one name. */
@Composable
fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, extra: @Composable () -> Unit = {}, onSave: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { text = it }, label = { Text("Name") }, singleLine = true)
                extra()
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
