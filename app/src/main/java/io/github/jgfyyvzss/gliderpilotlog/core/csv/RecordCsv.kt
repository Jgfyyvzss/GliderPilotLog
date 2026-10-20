package io.github.jgfyyvzss.gliderpilotlog.core.csv

import io.github.jgfyyvzss.gliderpilotlog.core.Keys

/** A checked gliders.csv or airfields.csv: rows to add, rows skipped because the record exists, and errors. */
data class RecordImport<T>(val records: List<T>, val skipped: List<String>, val errors: List<CsvError>, val warnings: List<String>)

data class ParsedGlider(val row: Int, val registration: String, val type: String, val weglideAircraftId: Long?)

data class ParsedAirfield(
    val row: Int, val name: String, val latitude: Double?, val longitude: Double?,
    val elevationM: Int?, val matchRadiusM: Int?, val clubs: List<String>,
)

/** gliders.csv from Appendix C. The WeGlide name is written for readability and ignored on import. */
object GliderCsv {
    val columns = listOf("registration", "type", "weglide_aircraft_id", "weglide_aircraft_name")

    data class ExportRow(val registration: String, val type: String, val weglideAircraftId: Long?, val weglideAircraftName: String?)

    fun export(rows: List<ExportRow>): String = Csv.file(
        columns,
        rows.map { listOf(it.registration, it.type, it.weglideAircraftId?.toString().orEmpty(), it.weglideAircraftName.orEmpty()) },
    )

    /** Rows whose registration already exists, here or earlier in the file, are skipped and reported, never updated. */
    fun parse(text: String, existingKeys: Set<String>): RecordImport<ParsedGlider> {
        val table = CsvTable.read(text, columns.toSet()) ?: return RecordImport(emptyList(), emptyList(), listOf(CsvError(1, null, null, "a header row")), emptyList())
        val errors = listOf("registration", "type").filter { !table.has(it) }.map { CsvError(1, it, null, "a $it column") }
        if (errors.isNotEmpty()) return RecordImport(emptyList(), emptyList(), errors, emptyList())
        val out = mutableListOf<ParsedGlider>()
        val skipped = mutableListOf<String>()
        val errs = mutableListOf<CsvError>()
        val seen = mutableSetOf<String>()
        for (r in table.rows) {
            val reg = r["registration"]
            val type = r["type"]
            val weglideCell = r["weglide_aircraft_id"]
            val weglide = weglideCell.toLongOrNull()
            val before = errs.size
            if (reg.isEmpty()) errs += CsvError(r.number, "registration", reg, "a registration")
            if (type.isEmpty()) errs += CsvError(r.number, "type", type, "the glider type")
            if (weglideCell.isNotEmpty() && (weglide == null || weglide <= 0)) errs += CsvError(r.number, "weglide_aircraft_id", weglideCell, "a whole number above 0")
            if (errs.size > before) continue
            val key = Keys.registrationKey(reg)
            when (key) {
                in existingKeys -> skipped += "Row ${r.number}: $reg is already in the glider list"
                in seen -> skipped += "Row ${r.number}: $reg appears earlier in the file"
                else -> { seen += key; out += ParsedGlider(r.number, reg, type, weglide) }
            }
        }
        return RecordImport(out, skipped, errs, table.unknownColumns.map { "Column $it is not used and was ignored" })
    }
}

/** airfields.csv from Appendix C. Club names are separated by semicolons. */
object AirfieldCsv {
    val columns = listOf("name", "latitude", "longitude", "elevation_m", "match_radius_m", "clubs")

    data class ExportRow(
        val name: String, val latitude: Double?, val longitude: Double?, val elevationM: Int?, val matchRadiusM: Int?, val clubs: List<String>,
    )

    fun export(rows: List<ExportRow>): String = Csv.file(
        columns,
        rows.map {
            listOf(
                it.name, it.latitude?.toString().orEmpty(), it.longitude?.toString().orEmpty(),
                it.elevationM?.toString().orEmpty(), it.matchRadiusM?.toString().orEmpty(), it.clubs.joinToString(";"),
            )
        },
    )

    fun parse(text: String, existingKeys: Set<String>): RecordImport<ParsedAirfield> {
        val table = CsvTable.read(text, columns.toSet()) ?: return RecordImport(emptyList(), emptyList(), listOf(CsvError(1, null, null, "a header row")), emptyList())
        if (!table.has("name")) return RecordImport(emptyList(), emptyList(), listOf(CsvError(1, "name", null, "a name column")), emptyList())
        val out = mutableListOf<ParsedAirfield>()
        val skipped = mutableListOf<String>()
        val errs = mutableListOf<CsvError>()
        val seen = mutableSetOf<String>()
        for (r in table.rows) {
            val before = errs.size
            val name = r["name"].also { if (it.isEmpty()) errs += CsvError(r.number, "name", it, "an airfield name") }
            fun dbl(col: String, range: ClosedFloatingPointRange<Double>, what: String): Double? {
                val cell = r[col]
                if (cell.isEmpty()) return null
                return cell.toDoubleOrNull()?.takeIf { it in range } ?: run { errs += CsvError(r.number, col, cell, what); null }
            }
            fun int(col: String, positive: Boolean): Int? {
                val cell = r[col]
                if (cell.isEmpty()) return null
                return cell.toIntOrNull()?.takeIf { !positive || it > 0 }
                    ?: run { errs += CsvError(r.number, col, cell, if (positive) "a whole number above 0" else "a whole number"); null }
            }
            val lat = dbl("latitude", -90.0..90.0, "decimal degrees between -90 and 90")
            val lon = dbl("longitude", -180.0..180.0, "decimal degrees between -180 and 180")
            if ((r["latitude"].isEmpty()) != (r["longitude"].isEmpty())) errs += CsvError(r.number, "longitude", r["longitude"], "both latitude and longitude, or neither")
            val elevation = int("elevation_m", positive = false)
            val radius = int("match_radius_m", positive = true)
            if (errs.size > before) continue
            val key = Keys.nameKey(name)
            when (key) {
                in existingKeys -> skipped += "Row ${r.number}: $name is already in the airfield list"
                in seen -> skipped += "Row ${r.number}: $name appears earlier in the file"
                else -> {
                    seen += key
                    val clubs = r["clubs"].split(';').map { it.trim() }.filter { it.isNotEmpty() }.distinctBy(Keys::nameKey)
                    out += ParsedAirfield(r.number, name, lat, lon, elevation, radius, clubs)
                }
            }
        }
        return RecordImport(out, skipped, errs, table.unknownColumns.map { "Column $it is not used and was ignored" })
    }
}
