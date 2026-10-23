package io.github.jgfyyvzss.gliderpilotlog.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.jgfyyvzss.gliderpilotlog.R
import io.github.jgfyyvzss.gliderpilotlog.ui.launchTypeName
import io.github.jgfyyvzss.gliderpilotlog.ui.text
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.jgfyyvzss.gliderpilotlog.core.HoursDisplay
import io.github.jgfyyvzss.gliderpilotlog.data.ClubEntity
import io.github.jgfyyvzss.gliderpilotlog.data.LaunchTypeEntity
import io.github.jgfyyvzss.gliderpilotlog.data.PilotIdentifier
import io.github.jgfyyvzss.gliderpilotlog.data.SaveResult
import io.github.jgfyyvzss.gliderpilotlog.data.SettingsRepository
import io.github.jgfyyvzss.gliderpilotlog.ui.Badge
import io.github.jgfyyvzss.gliderpilotlog.ui.airfields.NameDialog
import io.github.jgfyyvzss.gliderpilotlog.ui.appContainer
import io.github.jgfyyvzss.gliderpilotlog.ui.vmFactory
import io.github.jgfyyvzss.gliderpilotlog.ui.weglide.AircraftListSection
import io.github.jgfyyvzss.gliderpilotlog.ui.weglide.WeGlideAccountSection
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Holds the profile being edited, so typing survives rotation and is saved only on request. */
class ProfileViewModel(private val settings: SettingsRepository) : ViewModel() {
    var name by mutableStateOf("")
    val identifiers = mutableStateListOf<PilotIdentifier>()
    var loaded by mutableStateOf(false)
        private set
    var savedMessage by mutableStateOf<Int?>(null)

    init {
        viewModelScope.launch {
            val s = settings.settings.first()
            name = s.pilotName
            identifiers.addAll(s.pilotIdentifiers)
            loaded = true
        }
    }

    fun save() = viewModelScope.launch {
        settings.setPilotProfile(name, identifiers.toList())
        savedMessage = R.string.settings_profile_saved
    }
}

private val identifierSuggestions = listOf(R.string.settings_id_licence, R.string.settings_id_membership, R.string.settings_id_arn)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onClubs: () -> Unit, onLaunchTypes: () -> Unit, onOpeningBalances: () -> Unit, onSpreadsheets: () -> Unit) {
    val container = appContainer()
    val settings by container.settings.settings.collectAsStateWithLifecycle(null)
    val vm: ProfileViewModel = viewModel(factory = vmFactory { ProfileViewModel(it.settings) })
    val scope = rememberCoroutineScope()

    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.settings_profile), style = MaterialTheme.typography.titleMedium)
        if (vm.loaded) {
            OutlinedTextField(
                vm.name, { vm.name = it; vm.savedMessage = null },
                label = { Text(stringResource(R.string.common_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.settings_profile_name_hint), style = MaterialTheme.typography.bodySmall)
            vm.identifiers.forEachIndexed { i, id ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        id.label, { vm.identifiers[i] = id.copy(label = it); vm.savedMessage = null },
                        label = { Text(stringResource(R.string.settings_id_label)) }, singleLine = true, modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        id.value, { vm.identifiers[i] = id.copy(value = it); vm.savedMessage = null },
                        label = { Text(stringResource(R.string.settings_id_number)) }, singleLine = true, modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { vm.identifiers.removeAt(i); vm.savedMessage = null }) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.common_delete))
                    }
                }
                if (id.label.isBlank()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        identifierSuggestions.forEach { res ->
                            // The chosen label is stored as text in the pilot's language, like a label they typed.
                            val s = stringResource(res)
                            SuggestionChip(onClick = { vm.identifiers[i] = id.copy(label = s) }, label = { Text(s) })
                        }
                    }
                }
            }
            OutlinedButton(onClick = { vm.identifiers.add(PilotIdentifier("", "")) }) { Text(stringResource(R.string.settings_id_add)) }
            Button(onClick = { vm.save() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_profile_save)) }
            vm.savedMessage?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
        }

        HorizontalDivider()
        Text(stringResource(R.string.settings_flight_form), style = MaterialTheme.typography.titleMedium)
        settings?.let { s ->
            SwitchRow(
                stringResource(R.string.settings_record_place),
                stringResource(R.string.settings_record_place_hint),
                s.recordLandingPlace,
            ) { on -> scope.launch { container.settings.setRecordLandingPlace(on) } }
            SwitchRow(
                stringResource(R.string.settings_record_times),
                stringResource(R.string.settings_record_times_hint),
                s.recordTimes,
            ) { on -> scope.launch { container.settings.setRecordTimes(on) } }
        }

        HorizontalDivider()
        Text(stringResource(R.string.nav_totals), style = MaterialTheme.typography.titleMedium)
        settings?.let { s ->
            Text(stringResource(R.string.settings_hours_display))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = s.hoursDisplay == HoursDisplay.HM,
                    onClick = { scope.launch { container.settings.setHoursDisplay(HoursDisplay.HM) } },
                    label = { Text(stringResource(R.string.settings_hours_hm)) },
                )
                FilterChip(
                    selected = s.hoursDisplay == HoursDisplay.DECIMAL,
                    onClick = { scope.launch { container.settings.setHoursDisplay(HoursDisplay.DECIMAL) } },
                    label = { Text(stringResource(R.string.settings_hours_decimal)) },
                )
            }
            SwitchRow(
                stringResource(R.string.settings_instruction_pic),
                stringResource(R.string.settings_instruction_pic_hint),
                s.instructionCountsAsPic,
            ) { on -> scope.launch { container.settings.setInstructionCountsAsPic(on) } }
        }
        Text(stringResource(R.string.settings_balances_hint), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = onOpeningBalances, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.title_opening_balances)) }

        HorizontalDivider()
        BackupSection()

        HorizontalDivider()
        Text(stringResource(R.string.title_spreadsheets), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.settings_spreadsheets_hint), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = onSpreadsheets, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_spreadsheets_button)) }

        HorizontalDivider()
        Text(stringResource(R.string.settings_weglide), style = MaterialTheme.typography.titleMedium)
        AircraftListSection()
        WeGlideAccountSection()

        HorizontalDivider()
        Text(stringResource(R.string.settings_lists), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onClubs, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.title_clubs)) }
            OutlinedButton(onClick = onLaunchTypes, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.title_launch_types)) }
        }
    }
}

@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun ClubsScreen() {
    val repo = appContainer().repository
    val clubs by repo.observeClubs().collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var editing by rememberSaveable { mutableStateOf<String?>(null) } // a club id, or "" for a new club
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize()) {
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) } }
            if (clubs.isEmpty()) item { Text(stringResource(R.string.clubs_none_yet), Modifier.padding(16.dp)) }
            items(clubs, key = ClubEntity::id) { c ->
                ListItem(headlineContent = { Text(c.name) }, modifier = Modifier.clickable { editing = c.id; error = null })
                HorizontalDivider()
            }
        }
        FloatingActionButton(onClick = { editing = ""; error = null }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.clubs_new))
        }
    }

    editing?.let { id ->
        val club = clubs.firstOrNull { it.id == id }
        NameDialog(title = stringResource(if (club == null) R.string.clubs_new else R.string.clubs_rename), initial = club?.name ?: "", onDismiss = { editing = null }) { name ->
            scope.launch {
                (repo.saveClub(club?.id, name) as? SaveResult.Rejected)?.let { error = it.message.text(context) }
                editing = null
            }
        }
    }
}

@Composable
fun LaunchTypesScreen() {
    val repo = appContainer().repository
    val types by repo.observeLaunchTypes().collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var editing by rememberSaveable { mutableStateOf<String?>(null) } // a launch type id, or "" for a new one
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize()) {
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) } }
            items(types, key = LaunchTypeEntity::id) { t ->
                ListItem(
                    headlineContent = { Text(launchTypeName(t.id, t.name)) },
                    trailingContent = { if (t.archived) Badge(stringResource(R.string.common_archived)) },
                    modifier = Modifier.clickable { editing = t.id; error = null },
                )
                HorizontalDivider()
            }
        }
        FloatingActionButton(onClick = { editing = ""; error = null }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.launch_types_new))
        }
    }

    editing?.let { id ->
        val type = types.firstOrNull { it.id == id }
        var archived by rememberSaveable(id) { mutableStateOf(type?.archived ?: false) }
        NameDialog(
            title = stringResource(if (type == null) R.string.launch_types_new else R.string.flight_launch_type),
            initial = type?.name ?: "",
            onDismiss = { editing = null },
            extra = {
                if (type != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.common_archived), Modifier.weight(1f))
                        Switch(checked = archived, onCheckedChange = { archived = it })
                    }
                }
            },
        ) { name ->
            scope.launch {
                (repo.saveLaunchType(type?.id, name, archived) as? SaveResult.Rejected)?.let { error = it.message.text(context) }
                editing = null
            }
        }
    }
}
