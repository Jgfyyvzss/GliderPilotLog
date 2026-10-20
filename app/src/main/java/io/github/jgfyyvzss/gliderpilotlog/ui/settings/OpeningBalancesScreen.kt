package io.github.jgfyyvzss.gliderpilotlog.ui.settings

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
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.jgfyyvzss.gliderpilotlog.core.Durations
import io.github.jgfyyvzss.gliderpilotlog.core.Role
import io.github.jgfyyvzss.gliderpilotlog.data.LogbookRepository
import io.github.jgfyyvzss.gliderpilotlog.data.OpeningBalanceEntity
import io.github.jgfyyvzss.gliderpilotlog.data.SaveResult
import io.github.jgfyyvzss.gliderpilotlog.ui.ColonTransformation
import io.github.jgfyyvzss.gliderpilotlog.ui.shortText
import io.github.jgfyyvzss.gliderpilotlog.ui.vmFactory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** One role's balance as typed. */
data class BalanceDraft(val role: Role, val hoursText: String, val flightsText: String, val asOf: LocalDate?)

class OpeningBalancesViewModel(private val repo: LogbookRepository) : ViewModel() {
    val drafts = mutableStateListOf<BalanceDraft>()
    var loaded by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
    private var stored: Map<String, OpeningBalanceEntity> = emptyMap()

    init {
        viewModelScope.launch {
            stored = repo.observeOpeningBalances().first().associateBy { it.role }
            Role.entries.forEach { role ->
                val b = stored[role.name]
                drafts += BalanceDraft(
                    role,
                    hoursText = b?.minutes?.takeIf { it > 0 }?.let(Durations::formatHm) ?: "",
                    flightsText = b?.flights?.takeIf { it > 0 }?.toString() ?: "",
                    asOf = b?.asOfDate?.let(LocalDate::parse),
                )
            }
            loaded = true
        }
    }

    fun update(index: Int, draft: BalanceDraft) {
        drafts[index] = draft
        message = null
        error = null
    }

    fun save() = viewModelScope.launch {
        val rows = drafts.map { d ->
            val minutes = if (d.hoursText.isBlank()) 0 else Durations.parseFormEntry(d.hoursText)
            val flights = if (d.flightsText.isBlank()) 0 else d.flightsText.trim().toIntOrNull()
            if (minutes == null) { error = "${d.role.label}: enter hours as digits or H:MM, for example 12345 for 123:45"; return@launch }
            if (flights == null || flights < 0) { error = "${d.role.label}: enter flights as a whole number"; return@launch }
            OpeningBalanceEntity(role = d.role.name, asOfDate = d.asOf?.toString(), minutes = minutes, flights = flights, updatedAt = 0)
        }
        when (val r = repo.saveOpeningBalances(rows)) {
            is SaveResult.Saved -> message = "Opening balances saved"
            is SaveResult.Rejected -> error = r.message
        }
    }
}

/** One balance per role, entered once from the last paper logbook page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpeningBalancesScreen() {
    val vm: OpeningBalancesViewModel = viewModel(factory = vmFactory { OpeningBalancesViewModel(it.repository) })
    var pickingFor by rememberSaveable { mutableStateOf<Int?>(null) }
    if (!vm.loaded) return

    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Enter the totals from the last page of your paper logbook. They are added to Totals when no filter is active.",
            style = MaterialTheme.typography.bodySmall,
        )
        vm.drafts.forEachIndexed { i, d ->
            HorizontalDivider()
            Text(d.role.label, style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    d.hoursText, { vm.update(i, d.copy(hoursText = it)) }, label = { Text("Hours") },
                    placeholder = { Text("123:45") }, singleLine = true, visualTransformation = ColonTransformation,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = {
                        val m = Durations.parseFormEntry(d.hoursText)
                        if (m != null && ColonTransformation.display(d.hoursText.trim()) != Durations.formatHm(m)) Text("= ${Durations.formatHm(m)}")
                    },
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    d.flightsText, { vm.update(i, d.copy(flightsText = it.filter(Char::isDigit))) }, label = { Text("Flights") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
            Box(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    d.asOf?.shortText() ?: "", {}, readOnly = true, label = { Text("As of") },
                    placeholder = { Text("Date of the last paper entry") }, modifier = Modifier.fillMaxWidth(),
                )
                Box(Modifier.matchParentSize().clickable { pickingFor = i })
            }
        }
        vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = { vm.save() }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
        vm.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }

    pickingFor?.let { i ->
        val d = vm.drafts[i]
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (d.asOf ?: LocalDate.now()).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { pickingFor = null },
            confirmButton = {
                TextButton(onClick = {
                    vm.update(i, d.copy(asOf = state.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }))
                    pickingFor = null
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { vm.update(i, d.copy(asOf = null)); pickingFor = null }) { Text("Clear") }
            },
        ) { DatePicker(state) }
    }
}
