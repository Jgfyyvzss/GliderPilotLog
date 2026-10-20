package io.github.jgfyyvzss.gliderpilotlog.core.csv

import io.github.jgfyyvzss.gliderpilotlog.core.ClockTime
import io.github.jgfyyvzss.gliderpilotlog.core.Durations
import io.github.jgfyyvzss.gliderpilotlog.core.Keys
import io.github.jgfyyvzss.gliderpilotlog.core.Role
import java.time.LocalDate

/** Names already in the logbook, for matching imported rows. Keys come from [Keys]. */
data class KnownNames(
    val gliderKeys: Set<String> = emptySet(),
    val airfieldKeys: Set<String> = emptySet(),
    /** Launch type name key to id, archived types included. */
    val launchTypes: Map<String, String> = emptyMap(),
    val launchTypeNames: List<String> = emptyList(),
    val liveLogNumbers: Set<Int> = emptySet(),
)

/** One valid flights.csv row, with names as written and not yet resolved to records. */
data class ParsedFlight(
    val row: Int,
    val logNo: Int?,
    val date: LocalDate,
    val durationMinutes: Int,
    val glider: String,
    val airfield: String,
    val club: String?,
    /** Null when the cell was blank and the import's default launch type applies. */
    val launchTypeId: String?,
    val role: Role,
    /** Null when blank on a PIC, Instructor or Coach row, so the profile name applies. */
    val pic: String?,
    val launchTime: ClockTime?,
    val landingTime: ClockTime?,
    val landingAirfield: String?,
    val landingPlace: String?,
    val notes: String?,
)

/** The checked flights file, and what the confirmation screen must still ask. */
data class FlightImport(
    val flights: List<ParsedFlight>,
    val errors: List<CsvError>,
    val warnings: List<String>,
    /** Glider and airfield names with no match, by key, with the spelling first seen. */
    val unmatchedGliders: Map<String, String>,
    val unmatchedAirfields: Map<String, String>,
    val rowsNeedingLaunchType: Int,
    val needsNumbering: Boolean,
    val rowsNeedingProfileName: Int,
)

/** flights.csv from Appendix C. */
object FlightCsv {
    val exportColumns = listOf(
        "log_no", "date", "glider", "airfield", "club", "launch_type", "role", "pic", "duration",
        "launch_time", "landing_time", "landing_airfield", "landing_place", "notes",
    )
    private val required = listOf("date", "glider", "duration", "airfield", "role")
    const val MAX_ERRORS = 200

    /** One exported row, already turned into names and text. */
    data class ExportRow(
        val logNo: Int, val date: String, val glider: String, val airfield: String, val club: String?, val launchType: String,
        val role: String, val pic: String, val durationMinutes: Int, val launchTime: String?, val landingTime: String?,
        val landingAirfield: String?, val landingPlace: String?, val notes: String?,
    )

    fun export(rows: List<ExportRow>): String = Csv.file(
        exportColumns,
        rows.map {
            listOf(
                it.logNo.toString(), it.date, it.glider, it.airfield, it.club.orEmpty(), it.launchType, it.role, it.pic,
                Durations.formatHm(it.durationMinutes), it.launchTime.orEmpty(), it.landingTime.orEmpty(),
                it.landingAirfield.orEmpty(), it.landingPlace.orEmpty(), it.notes.orEmpty(),
            )
        },
    )

    /** Checks the whole file. Nothing may be imported while [FlightImport.errors] is not empty. */
    fun parse(text: String, known: KnownNames): FlightImport {
        val table = CsvTable.read(text, exportColumns.toSet())
            ?: return FlightImport(emptyList(), listOf(CsvError(1, null, null, "a header row")), emptyList(), emptyMap(), emptyMap(), 0, false, 0)
        val errors = mutableListOf<CsvError>()
        required.filter { !table.has(it) }.forEach { errors += CsvError(1, it, null, "a $it column") }
        if (errors.isNotEmpty()) return FlightImport(emptyList(), errors, emptyList(), emptyMap(), emptyMap(), 0, false, 0)

        val warnings = table.unknownColumns.map { "Column $it is not used and was ignored" }
        val hasLogNo = table.has("log_no")
        val flights = mutableListOf<ParsedFlight>()
        val unmatchedGliders = linkedMapOf<String, String>()
        val unmatchedAirfields = linkedMapOf<String, String>()
        val seenLogNos = mutableMapOf<Int, Int>()
        var needLaunchType = 0
        var needProfile = 0
        val launchTypeList = known.launchTypeNames.joinToString(", ")

        for (r in table.rows) {
            val before = errors.size
            fun err(column: String, found: String, expected: String) { errors += CsvError(r.number, column, found, expected) }

            val logNo = if (!hasLogNo) null else r["log_no"].let { cell ->
                val n = cell.toIntOrNull()
                when {
                    n == null || n <= 0 -> { err("log_no", cell, "a whole number above 0"); null }
                    n in known.liveLogNumbers -> { err("log_no", cell, "a log number not already in the logbook"); null }
                    n in seenLogNos -> { err("log_no", cell, "a log number not used earlier in the file (row ${seenLogNos[n]})"); null }
                    else -> { seenLogNos[n] = r.number; n }
                }
            }
            val date = r["date"].let { cell -> runCatching { LocalDate.parse(cell) }.getOrNull() ?: run { err("date", cell, "YYYY-MM-DD"); null } }
            val duration = r["duration"].let { cell ->
                val m = if (cell.contains(':')) Durations.parseHm(cell) else cell.toIntOrNull()
                when {
                    m == null -> { err("duration", cell, "H:MM or whole minutes"); null }
                    !Durations.isValidFlightDuration(m) -> { err("duration", cell, "between 1 minute and 24:00"); null }
                    else -> m
                }
            }
            val glider = r["glider"].also { if (it.isEmpty()) err("glider", it, "a glider registration") }
            val airfield = r["airfield"].also { if (it.isEmpty()) err("airfield", it, "an airfield name") }
            val role = r["role"].let { cell ->
                Role.entries.firstOrNull { it.name.equals(cell, ignoreCase = true) }
                    ?: run { err("role", cell, "PIC, STUDENT, INSTRUCTOR, COACH or MUTUAL"); null }
            }
            val launchTypeCell = r["launch_type"]
            val launchTypeId = if (launchTypeCell.isEmpty()) null else known.launchTypes[Keys.nameKey(launchTypeCell)]
                ?: run { err("launch_type", launchTypeCell, "one of $launchTypeList"); null }
            val pic = r["pic"].ifEmpty { null }
            if (pic == null && role != null && role.picIsSomeoneElse) err("pic", "", "the name of the pilot in command on a ${role.name} row")
            val launch = r["launch_time"].let { cell ->
                if (cell.isEmpty()) null else ClockTime.parse(cell) ?: run { err("launch_time", cell, "HH:MM"); null }
            }
            val landing = r["landing_time"].let { cell ->
                if (cell.isEmpty()) null else ClockTime.parse(cell) ?: run { err("landing_time", cell, "HH:MM"); null }
            }
            if (launch != null && landing != null && ClockTime.minutesBetween(launch, landing) == null) {
                err("landing_time", r["landing_time"], "a time after the launch time ${r["launch_time"]}")
            }
            val landingAirfield = r["landing_airfield"].ifEmpty { null }
            val landingPlace = r["landing_place"].ifEmpty { null }
            if (landingAirfield != null && landingPlace != null) {
                err("landing_place", landingPlace, "landing_airfield or landing_place, not both")
            }

            if (errors.size > before) continue
            if (launchTypeCell.isEmpty()) needLaunchType++
            if (pic == null) needProfile++
            if (Keys.registrationKey(glider) !in known.gliderKeys) unmatchedGliders.putIfAbsent(Keys.registrationKey(glider), glider)
            listOfNotNull(airfield, landingAirfield).forEach { a ->
                if (Keys.nameKey(a) !in known.airfieldKeys) unmatchedAirfields.putIfAbsent(Keys.nameKey(a), a)
            }
            flights += ParsedFlight(
                r.number, logNo, date!!, duration!!, glider, airfield, r["club"].ifEmpty { null }, launchTypeId, role!!, pic,
                launch, landing, landingAirfield, landingPlace, r["notes"].ifEmpty { null },
            )
        }
        if (table.rows.isEmpty()) errors += CsvError(2, null, null, "at least one flight row")
        return FlightImport(flights, errors, warnings, unmatchedGliders, unmatchedAirfields, needLaunchType, !hasLogNo, needProfile)
    }
}
