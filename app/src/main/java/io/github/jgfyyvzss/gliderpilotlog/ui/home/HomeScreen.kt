package io.github.jgfyyvzss.gliderpilotlog.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.jgfyyvzss.gliderpilotlog.R
import io.github.jgfyyvzss.gliderpilotlog.ui.label
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jgfyyvzss.gliderpilotlog.core.FlightFilter
import io.github.jgfyyvzss.gliderpilotlog.core.HoursFormat
import io.github.jgfyyvzss.gliderpilotlog.core.Role
import io.github.jgfyyvzss.gliderpilotlog.core.RoleTotal
import io.github.jgfyyvzss.gliderpilotlog.core.backup.BackupSchedule
import io.github.jgfyyvzss.gliderpilotlog.data.Settings
import io.github.jgfyyvzss.gliderpilotlog.ui.appContainer
import io.github.jgfyyvzss.gliderpilotlog.ui.settings.lastBackupText

/** New flight, a compact unfiltered totals card, and the last backup. From IGC arrives in step 6. */
@Composable
fun HomeScreen(onNewFlight: () -> Unit, onTotals: () -> Unit, onBackup: () -> Unit) {
    val app = appContainer()
    val tableFlow = remember { app.totals.observe(FlightFilter()) }
    val table by tableFlow.collectAsStateWithLifecycle(null)
    val settings by app.settings.settings.collectAsStateWithLifecycle(Settings())
    val flightsFlow = remember { app.repository.observeTotalsByRole(FlightFilter()) }
    val byRole by flightsFlow.collectAsStateWithLifecycle(emptyMap())
    val hasFlights = byRole.values.any { it.flights > 0 }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(onClick = onNewFlight, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Icon(Icons.Default.Add, contentDescription = null)
            Text("  " + stringResource(R.string.home_new_flight))
        }
        table?.let { t ->
            Card(Modifier.fillMaxWidth().clickable(onClick = onTotals)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.nav_totals), style = MaterialTheme.typography.titleMedium)
                    CardRow(Role.PIC.label() + if (t.picIncludesInstruction) " *" else "", t.rows.getValue(Role.PIC), settings)
                    CardRow(stringResource(R.string.totals_combined), t.combined, settings)
                }
            }
        }
        // Nudges when there are flights and the last backup is stale or missing.
        val stale = hasFlights && BackupSchedule.isStale(settings.lastBackupAt, System.currentTimeMillis())
        Text(
            stringResource(if (stale) R.string.home_last_backup_stale else R.string.home_last_backup, lastBackupText(settings.lastBackupAt)),
            color = if (stale) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onBackup).padding(vertical = 8.dp),
        )
    }
}

@Composable
private fun CardRow(label: String, total: RoleTotal, settings: Settings) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1.4f))
        Text(HoursFormat.format(total.minutes, settings.hoursDisplay), Modifier.weight(1f), textAlign = TextAlign.End)
        Text(pluralStringResource(R.plurals.home_flights, total.flights, total.flights), Modifier.weight(1.2f), textAlign = TextAlign.End)
    }
}
