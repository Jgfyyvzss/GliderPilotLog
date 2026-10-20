package io.github.jgfyyvzss.gliderpilotlog.core.backup

import io.github.jgfyyvzss.gliderpilotlog.core.ClockTime
import io.github.jgfyyvzss.gliderpilotlog.core.Durations
import io.github.jgfyyvzss.gliderpilotlog.core.Role
import java.time.LocalDate

/**
 * Checks a backup before anything is written: references, enum values, durations, timestamps and the
 * uniqueness rules that the database enforces on live rows. Returns the problems found, empty when valid.
 */
object BackupValidation {
    private val sources = setOf("MANUAL", "IGC", "CSV")
    private val roles = Role.entries.map { it.name }.toSet()

    fun validate(file: BackupFile): List<String> {
        val p = mutableListOf<String>()
        fun ts(what: String, value: String?, nullable: Boolean = false) {
            if (value == null) { if (!nullable) p += "$what has no time"; return }
            if (BackupTime.fromIso(value) == null) p += "$what has an unreadable time: $value"
        }
        fun <T> dupIds(table: String, rows: List<T>, id: (T) -> String) {
            rows.groupingBy(id).eachCount().filter { it.value > 1 }.keys.forEach { p += "$table: id $it appears more than once" }
        }

        dupIds("Launch types", file.launchTypes) { it.id }
        dupIds("Clubs", file.clubs) { it.id }
        dupIds("Airfields", file.airfields) { it.id }
        dupIds("Gliders", file.gliders) { it.id }
        dupIds("Flights", file.flights) { it.id }
        dupIds("Import batches", file.importBatches) { it.id }

        val launchTypeIds = file.launchTypes.map { it.id }.toSet()
        val clubIds = file.clubs.map { it.id }.toSet()
        val airfieldIds = file.airfields.map { it.id }.toSet()
        val gliderIds = file.gliders.map { it.id }.toSet()

        file.launchTypes.forEach { t ->
            if (t.name.isBlank()) p += "Launch type ${t.id} has no name"
            ts("Launch type ${t.name}", t.updatedAt); ts("Launch type ${t.name}", t.deletedAt, nullable = true)
        }
        file.clubs.forEach { c ->
            if (c.name.isBlank()) p += "Club ${c.id} has no name"
            ts("Club ${c.name}", c.updatedAt); ts("Club ${c.name}", c.deletedAt, nullable = true)
        }
        file.airfields.forEach { a ->
            val n = "Airfield ${a.name}"
            if (a.name.isBlank()) p += "Airfield ${a.id} has no name"
            a.clubIds.filter { it !in clubIds }.forEach { p += "$n refers to a missing club $it" }
            if (a.defaultClubId != null && a.defaultClubId !in a.clubIds) p += "$n has a default club that is not one of its clubs"
            if (a.defaultLaunchTypeId != null && a.defaultLaunchTypeId !in launchTypeIds) p += "$n refers to a missing launch type"
            if ((a.latitude == null) != (a.longitude == null)) p += "$n has only one coordinate"
            a.latitude?.let { if (it !in -90.0..90.0) p += "$n has latitude out of range" }
            a.longitude?.let { if (it !in -180.0..180.0) p += "$n has longitude out of range" }
            ts(n, a.updatedAt); ts(n, a.deletedAt, nullable = true)
        }
        file.gliders.forEach { g ->
            if (g.registration.isBlank()) p += "Glider ${g.id} has no registration"
            g.logbookTotalDate?.let { if (parseDate(it) == null) p += "Glider ${g.registration} has an unreadable logbook date" }
            ts("Glider ${g.registration}", g.updatedAt); ts("Glider ${g.registration}", g.deletedAt, nullable = true)
        }
        file.flights.forEach { f ->
            val n = "Flight ${f.logNo}"
            if (f.logNo <= 0) p += "$n: the log number must be above 0"
            if (parseDate(f.date) == null) p += "$n: unreadable date ${f.date}"
            if (!Durations.isValidFlightDuration(f.durationMinutes)) p += "$n: duration ${f.durationMinutes} minutes is out of range"
            if (f.gliderId !in gliderIds) p += "$n refers to a missing glider"
            if (f.airfieldId !in airfieldIds) p += "$n refers to a missing airfield"
            if (f.clubId != null && f.clubId !in clubIds) p += "$n refers to a missing club"
            if (f.launchTypeId !in launchTypeIds) p += "$n refers to a missing launch type"
            if (f.landingAirfieldId != null && f.landingAirfieldId !in airfieldIds) p += "$n refers to a missing landing airfield"
            if (f.landingAirfieldId != null && f.landingPlace != null) p += "$n has both a landing airfield and a landing place"
            if (f.role !in roles) p += "$n: unknown role ${f.role}"
            if (f.source !in sources) p += "$n: unknown source ${f.source}"
            if (f.picName.isBlank()) p += "$n has no pilot in command"
            val launch = f.launchTime?.let { ClockTime.parse(it) ?: run { p += "$n: unreadable launch time"; null } }
            val landing = f.landingTime?.let { ClockTime.parse(it) ?: run { p += "$n: unreadable landing time"; null } }
            if (launch != null && landing != null && ClockTime.minutesBetween(launch, landing) == null) p += "$n: landing is not after launch"
            ts(n, f.updatedAt); ts(n, f.deletedAt, nullable = true)
        }
        file.openingBalances.forEach { b ->
            if (b.role !in roles) p += "Opening balance for unknown role ${b.role}"
            if (b.minutes < 0 || b.flights < 0) p += "Opening balance for ${b.role} is negative"
            b.asOfDate?.let { if (parseDate(it) == null) p += "Opening balance for ${b.role} has an unreadable date" }
            ts("Opening balance ${b.role}", b.updatedAt)
        }
        file.openingBalances.groupingBy { it.role }.eachCount().filter { it.value > 1 }.keys
            .forEach { p += "Opening balance for $it appears more than once" }
        file.importBatches.forEach { ts("Import batch ${it.fileName}", it.createdAt) }

        p += liveCollisions(file)
        return p
    }

    /** Live rows that would break a uniqueness rule, such as two different flights with one log number. */
    fun liveCollisions(file: BackupFile): List<String> {
        val p = mutableListOf<String>()
        val gliderReg = file.gliders.associate { it.id to it.registration }
        file.flights.filter { it.deletedAt == null }.groupBy { it.logNo }.filter { it.value.size > 1 }.forEach { (logNo, flights) ->
            val which = flights.joinToString(" and ") { "the flight on ${it.date} in ${gliderReg[it.gliderId] ?: "?"}" }
            p += "Log number $logNo is used by two different flights: $which"
        }
        fun <T> unique(what: String, rows: List<T>, live: (T) -> Boolean, key: (T) -> String, label: (T) -> String) {
            rows.filter(live).groupBy(key).filter { it.value.size > 1 }.values
                .forEach { same -> p += "$what ${label(same.first())} appears more than once with different ids" }
        }
        unique("Glider", file.gliders, { it.deletedAt == null }, { it.registrationKey }, { it.registration })
        unique("Airfield", file.airfields, { it.deletedAt == null }, { it.nameKey }, { it.name })
        unique("Club", file.clubs, { it.deletedAt == null }, { it.nameKey }, { it.name })
        unique("Launch type", file.launchTypes, { it.deletedAt == null }, { it.nameKey }, { it.name })
        return p
    }

    private fun parseDate(text: String): LocalDate? = runCatching { LocalDate.parse(text) }.getOrNull()
}
