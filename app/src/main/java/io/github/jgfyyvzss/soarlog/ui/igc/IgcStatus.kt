package io.github.jgfyyvzss.soarlog.ui.igc

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.jgfyyvzss.soarlog.R
import io.github.jgfyyvzss.soarlog.core.Durations
import io.github.jgfyyvzss.soarlog.core.igc.StoredIgc
import io.github.jgfyyvzss.soarlog.ui.shortText
import java.time.ZoneId

/** Whether the file's flights are in the logbook, shown wherever the file is so neither job is forgotten. */
@Composable
fun logbookStatus(f: StoredIgc): String = when {
    f.flights == 0 -> stringResource(R.string.igc_status_logbook_none)
    f.fullyLogged -> stringResource(R.string.igc_status_logbook_done)
    f.logged.isEmpty() -> stringResource(R.string.igc_status_logbook_not)
    else -> stringResource(R.string.igc_status_logbook_part, f.logged.size, f.flights)
}

/** Whether the file is on WeGlide. */
@Composable
fun weglideStatus(f: StoredIgc): String = when {
    !f.uploaded -> stringResource(R.string.igc_status_weglide_not)
    f.weglideFlightId != null -> stringResource(R.string.igc_status_weglide_done_id, f.weglideFlightId.toString())
    else -> stringResource(R.string.igc_status_weglide_done)
}

/** For example `13/02/25, launch 12:28, 0:06, glider VH-FQM`, from the details stored with the file. */
@Composable
fun fileSummary(f: StoredIgc): String {
    val launch = f.firstLaunch?.atZone(ZoneId.systemDefault()) ?: return stringResource(R.string.upload_no_flight_found)
    return stringResource(
        R.string.upload_summary,
        launch.toLocalDate().shortText(),
        launch.toLocalTime().withSecond(0).withNano(0).toString(),
        Durations.formatHm(f.firstDurationMinutes ?: 0),
        f.gliderIdHeader ?: "?",
    )
}
