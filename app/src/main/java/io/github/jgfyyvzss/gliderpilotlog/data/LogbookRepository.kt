package io.github.jgfyyvzss.gliderpilotlog.data

import androidx.paging.PagingSource
import androidx.room.withTransaction
import io.github.jgfyyvzss.gliderpilotlog.core.FlightContext
import io.github.jgfyyvzss.gliderpilotlog.core.FlightDraft
import io.github.jgfyyvzss.gliderpilotlog.core.FlightFilter
import io.github.jgfyyvzss.gliderpilotlog.core.FlightRules
import io.github.jgfyyvzss.gliderpilotlog.core.FlightValidation
import io.github.jgfyyvzss.gliderpilotlog.core.Keys
import io.github.jgfyyvzss.gliderpilotlog.core.Msg
import io.github.jgfyyvzss.gliderpilotlog.core.MsgKey
import io.github.jgfyyvzss.gliderpilotlog.core.msg
import io.github.jgfyyvzss.gliderpilotlog.core.Role
import io.github.jgfyyvzss.gliderpilotlog.core.RoleTotal
import io.github.jgfyyvzss.gliderpilotlog.core.weglide.WeGlideAircraft
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The outcome of saving a record. */
sealed interface SaveResult {
    data class Saved(val id: String) : SaveResult
    data class Rejected(val message: Msg) : SaveResult
}

class LogbookRepository(
    private val db: LogbookDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val gliders = db.gliderDao()
    private val airfields = db.airfieldDao()
    private val clubs = db.clubDao()
    private val launchTypes = db.launchTypeDao()
    private val flights = db.flightDao()
    private val openingBalances = db.openingBalanceDao()

    // Gliders

    fun observeGliders(): Flow<List<GliderEntity>> = gliders.observeAll()
    suspend fun glider(id: String) = gliders.get(id)

    suspend fun saveGlider(id: String?, registration: String, type: String, archived: Boolean): SaveResult {
        val reg = registration.trim()
        if (reg.isEmpty()) return SaveResult.Rejected(msg(MsgKey.REGISTRATION_REQUIRED))
        val key = Keys.registrationKey(reg)
        gliders.findLiveByKey(key, id ?: "")?.let {
            return SaveResult.Rejected(msg(MsgKey.GLIDER_EXISTS, it.registration))
        }
        val existing = id?.let { gliders.get(it) }
        val row = (existing ?: GliderEntity(id = Keys.newId(), registration = reg, registrationKey = key, type = "", incomplete = true, updatedAt = 0))
            .copy(registration = reg, registrationKey = key, type = type.trim(), incomplete = type.isBlank(), archived = archived, updatedAt = now())
        if (existing == null) gliders.insert(row) else gliders.update(row)
        return SaveResult.Saved(row.id)
    }

    fun observeGlider(id: String): Flow<GliderEntity?> = gliders.observe(id)

    /**
     * Stores the total last written in the aircraft logbook, as the calculator's default. Minutes and date are set
     * together; the flight count is optional. Passing null minutes clears all three.
     */
    suspend fun saveLogbookTotal(gliderId: String, minutes: Int?, flights: Int?, date: LocalDate?): SaveResult {
        val g = gliders.get(gliderId) ?: return SaveResult.Rejected(msg(MsgKey.GLIDER_GONE))
        if (minutes != null && (minutes < 0 || date == null)) return SaveResult.Rejected(msg(MsgKey.LOGBOOK_HOURS_AND_DATE))
        if (flights != null && flights < 0) return SaveResult.Rejected(msg(MsgKey.FLIGHTS_NEGATIVE))
        gliders.update(
            g.copy(
                logbookTotalMinutes = minutes,
                logbookTotalFlights = flights.takeIf { minutes != null },
                logbookTotalDate = date?.toString().takeIf { minutes != null },
                updatedAt = now(),
            ),
        )
        return SaveResult.Saved(gliderId)
    }

    /**
     * Sets the glider's WeGlide aircraft and replaces its type with WeGlide's name, which is lost once saved but stays
     * editable by hand. Null clears the ID and keeps the type as it is.
     */
    suspend fun setWeGlideAircraft(gliderId: String, aircraft: WeGlideAircraft?): SaveResult {
        val g = gliders.get(gliderId) ?: return SaveResult.Rejected(msg(MsgKey.GLIDER_GONE))
        val type = aircraft?.name ?: g.type
        gliders.update(g.copy(weglideAircraftId = aircraft?.id, type = type, incomplete = type.isBlank(), updatedAt = now()))
        return SaveResult.Saved(gliderId)
    }

    /** The glider's live flights on [date], and the gliders flown that day. */
    suspend fun flightsOnDay(gliderId: String, date: LocalDate) = flights.onDay(gliderId, date.toString())
    suspend fun glidersOnDay(date: LocalDate) = flights.glidersOnDay(date.toString())

    // Airfields and clubs

    fun observeAirfieldList(): Flow<List<AirfieldListRow>> = airfields.observeList()
    fun observeAirfields(): Flow<List<AirfieldEntity>> = airfields.observeAll()
    suspend fun airfield(id: String) = airfields.get(id)
    suspend fun clubsFor(airfieldId: String) = airfields.clubsFor(airfieldId)

    suspend fun saveAirfield(
        id: String?,
        name: String,
        latitude: Double?,
        longitude: Double?,
        elevationM: Int?,
        matchRadiusM: Int?,
        clubIds: List<String>,
        archived: Boolean,
        defaultClubId: String? = null,
        defaultLaunchTypeId: String? = null,
    ): SaveResult {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return SaveResult.Rejected(msg(MsgKey.AIRFIELD_NAME_REQUIRED))
        if ((latitude == null) != (longitude == null)) return SaveResult.Rejected(msg(MsgKey.COORDINATES_BOTH))
        if (latitude != null && latitude !in -90.0..90.0) return SaveResult.Rejected(msg(MsgKey.LATITUDE_RANGE))
        if (longitude != null && longitude !in -180.0..180.0) return SaveResult.Rejected(msg(MsgKey.LONGITUDE_RANGE))
        if (matchRadiusM != null && matchRadiusM <= 0) return SaveResult.Rejected(msg(MsgKey.RADIUS_POSITIVE))
        val key = Keys.nameKey(trimmed)
        airfields.findLiveByKey(key, id ?: "")?.let {
            return SaveResult.Rejected(msg(MsgKey.AIRFIELD_EXISTS, it.name))
        }
        return db.withTransaction {
            val existing = id?.let { airfields.get(it) }
            val row = (existing ?: AirfieldEntity(id = Keys.newId(), name = trimmed, nameKey = key, incomplete = true, updatedAt = 0))
                .copy(
                    name = trimmed, nameKey = key, latitude = latitude, longitude = longitude,
                    elevationM = elevationM, matchRadiusM = matchRadiusM,
                    // The default club must be one of the airfield's own clubs.
                    defaultClubId = defaultClubId?.takeIf { it in clubIds },
                    defaultLaunchTypeId = defaultLaunchTypeId,
                    incomplete = latitude == null, archived = archived, updatedAt = now(),
                )
            if (existing == null) airfields.insert(row) else airfields.update(row)
            airfields.replaceLinks(row.id, clubIds)
            SaveResult.Saved(row.id)
        }
    }

    fun observeClubs(): Flow<List<ClubEntity>> = clubs.observeAll()

    suspend fun saveClub(id: String?, name: String): SaveResult {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return SaveResult.Rejected(msg(MsgKey.CLUB_NAME_REQUIRED))
        val key = Keys.nameKey(trimmed)
        clubs.findLiveByKey(key, id ?: "")?.let {
            return SaveResult.Rejected(msg(MsgKey.CLUB_EXISTS, it.name))
        }
        val existing = id?.let { clubs.get(it) }
        val row = (existing ?: ClubEntity(id = Keys.newId(), name = trimmed, nameKey = key, updatedAt = 0))
            .copy(name = trimmed, nameKey = key, updatedAt = now())
        if (existing == null) clubs.insert(row) else clubs.update(row)
        return SaveResult.Saved(row.id)
    }

    // Launch types

    fun observeLaunchTypes(): Flow<List<LaunchTypeEntity>> = launchTypes.observeAll()

    suspend fun saveLaunchType(id: String?, name: String, archived: Boolean): SaveResult {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return SaveResult.Rejected(msg(MsgKey.LAUNCH_TYPE_NAME_REQUIRED))
        val key = Keys.nameKey(trimmed)
        launchTypes.findLiveByKey(key, id ?: "")?.let {
            return SaveResult.Rejected(msg(MsgKey.LAUNCH_TYPE_EXISTS, it.name))
        }
        val existing = id?.let { launchTypes.get(it) }
        val row = existing?.copy(name = trimmed, nameKey = key, archived = archived, updatedAt = now())
            ?: LaunchTypeEntity(
                id = Keys.newId(), name = trimmed, nameKey = key,
                sortOrder = launchTypes.maxSortOrder() + 1, archived = archived, updatedAt = now(),
            )
        if (existing == null) launchTypes.insert(row) else launchTypes.update(row)
        return SaveResult.Saved(row.id)
    }

    // Flights

    fun pagedFlights(filter: FlightFilter = FlightFilter()): PagingSource<Int, FlightListRow> =
        flights.pagedList(FlightQueries.list(filter))

    /** Minutes and flights per role for the live flights matching [filter]. */
    fun observeTotalsByRole(filter: FlightFilter): Flow<Map<Role, RoleTotal>> =
        flights.totalsByRole(FlightQueries.totalsByRole(filter)).map { rows ->
            rows.mapNotNull { r -> Role.entries.firstOrNull { it.name == r.role }?.let { it to RoleTotal(r.minutes, r.flights) } }.toMap()
        }

    // Opening balances

    fun observeOpeningBalances(): Flow<List<OpeningBalanceEntity>> = openingBalances.observeAll()

    /** Saves the five balances together. Minutes and flights must not be negative. */
    suspend fun saveOpeningBalances(balances: List<OpeningBalanceEntity>): SaveResult {
        if (balances.any { it.minutes < 0 || it.flights < 0 }) return SaveResult.Rejected(msg(MsgKey.BALANCES_NEGATIVE))
        val time = now()
        openingBalances.update(balances.map { it.copy(updatedAt = time) })
        return SaveResult.Saved("opening_balance")
    }
    suspend fun flight(id: String) = flights.get(id)

    /** The highest live log number plus one, or 1 for the first flight. */
    suspend fun nextLogNo(): Int = (flights.maxLogNo() ?: 0) + 1

    suspend fun validateFlight(id: String?, draft: FlightDraft): FlightValidation {
        val logNo = FlightRules.parseLogNo(draft.logNoText)
        val holder = logNo?.let { flights.liveHolderOf(it, id ?: "") }
        val clubCount = draft.airfieldId?.let { airfields.clubsFor(it).size } ?: 0
        return FlightRules.validate(draft, FlightContext(clubCount, holder?.describe()))
    }

    /** Validates and saves. Warnings do not block saving; the form shows them before the pilot confirms. */
    suspend fun saveFlight(id: String?, draft: FlightDraft): Pair<FlightValidation, String?> =
        db.withTransaction {
            val result = validateFlight(id, draft)
            if (result !is FlightValidation.Valid) return@withTransaction result to null
            val f = result.flight
            val existing = id?.let { flights.get(it) }
            val row = FlightEntity(
                id = existing?.id ?: Keys.newId(),
                logNo = f.logNo,
                date = f.date.toString(),
                durationMinutes = f.durationMinutes,
                gliderId = f.gliderId,
                airfieldId = f.airfieldId,
                clubId = f.clubId,
                launchTypeId = f.launchTypeId,
                role = f.role.name,
                picName = f.picName,
                launchTime = f.launchTime?.toString(),
                landingTime = f.landingTime?.toString(),
                landingAirfieldId = f.landingAirfieldId,
                landingPlace = f.landingPlace,
                notes = f.notes,
                source = existing?.source ?: FlightSource.MANUAL.name,
                importBatchId = existing?.importBatchId,
                updatedAt = now(),
            )
            if (existing == null) flights.insert(row) else flights.update(row)
            result to row.id
        }

    /** Deletes a flight by leaving a tombstone, which frees its log number. */
    suspend fun deleteFlight(id: String) {
        val existing = flights.get(id) ?: return
        val time = now()
        flights.update(existing.copy(updatedAt = time, deletedAt = time))
    }

    /** "the flight on 17/10/26 in FQM", with the date shown in the pilot's format by the UI. */
    private fun FlightLabel.describe(): Msg =
        msg(MsgKey.FLIGHT_ON, runCatching { LocalDate.parse(date) }.getOrDefault(date), registration)
}
