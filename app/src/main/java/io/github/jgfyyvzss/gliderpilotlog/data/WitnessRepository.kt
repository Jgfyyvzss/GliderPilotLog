package io.github.jgfyyvzss.gliderpilotlog.data

import androidx.room.withTransaction
import io.github.jgfyyvzss.gliderpilotlog.core.Keys
import io.github.jgfyyvzss.gliderpilotlog.core.witness.SealInput
import io.github.jgfyyvzss.gliderpilotlog.core.witness.SealStatus
import io.github.jgfyyvzss.gliderpilotlog.core.witness.WitnessSeal
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** Why a sign-off was refused. The profile check is the sign-off screen's, with the pilot's profile at hand. */
enum class SignRefusal { NO_NAME, NO_CONSENT, NO_FLIGHTS, NO_PHOTO, PHOTO_TOO_LARGE }

sealed interface SignResult {
    data class Signed(val signoffId: String) : SignResult
    data class Refused(val reason: SignRefusal) : SignResult
}

/**
 * Witness sign-off (docs/Witness_Sign-Off.md): one sign-off covers a batch of flights, and each flight gets its own
 * seal. The rules a Room annotation cannot express are kept here: a name, consent, a photo (the evidence of who
 * witnessed) of at most [MAX_PHOTO_BYTES], and a flight's two witness columns set together or not at all.
 */
class WitnessRepository(private val db: LogbookDatabase, private val now: () -> Long = System::currentTimeMillis) {
    private val dao = db.witnessDao()
    private val flights = db.flightDao()

    fun observePicks(): Flow<List<WitnessPick>> = dao.observePicks()

    suspend fun signoff(id: String): WitnessSignoffEntity? = dao.get(id)

    /**
     * Saves one sign-off and seals each live flight in [flightIds]. A flight witnessed before is witnessed again,
     * and a sign-off left with no flights is deleted.
     */
    suspend fun sign(flightIds: List<String>, name: String, identifier: String?, photo: ByteArray, consent: Boolean): SignResult {
        if (name.isBlank()) return SignResult.Refused(SignRefusal.NO_NAME)
        if (!consent) return SignResult.Refused(SignRefusal.NO_CONSENT)
        if (photo.isEmpty()) return SignResult.Refused(SignRefusal.NO_PHOTO)
        if (photo.size > MAX_PHOTO_BYTES) return SignResult.Refused(SignRefusal.PHOTO_TOO_LARGE)
        return db.withTransaction {
            val ticked = flightIds.distinct().mapNotNull { flights.get(it) }.filter { it.deletedAt == null }
            if (ticked.isEmpty()) return@withTransaction SignResult.Refused(SignRefusal.NO_FLIGHTS)
            val time = now()
            val signoff = WitnessSignoffEntity(
                id = Keys.newId(),
                witnessName = name.trim(),
                witnessIdentifier = identifier?.trim()?.takeIf { it.isNotEmpty() },
                signedAt = time,
                photo = photo,
                photoSha256 = WitnessSeal.sha256Hex(photo),
                sealVersion = WitnessSeal.VERSION,
                consentConfirmed = true,
                updatedAt = time,
            )
            dao.insert(signoff)
            ticked.forEach { f -> dao.setWitness(f.id, signoff.id, WitnessSeal.seal(sealInput(f, signoff)), time) }
            dao.deleteUnreferenced(time)
            SignResult.Signed(signoff.id)
        }
    }

    /** Deletes the photo but keeps its hash, the name, the number and every seal. */
    suspend fun removePhoto(signoffId: String) {
        val s = dao.get(signoffId) ?: return
        if (s.photo == null && s.photoRemoved) return
        dao.update(s.copy(photo = null, photoRemoved = true, updatedAt = now()))
    }

    /** Detaches the witness from one flight; a sign-off no live flight refers to is then deleted with its photo. */
    suspend fun removeMark(flightId: String) = db.withTransaction {
        val time = now()
        dao.setWitness(flightId, null, null, time)
        dao.deleteUnreferenced(time)
    }

    /** Whether a flight still matches its seal, or null when it is not witnessed. */
    suspend fun status(flight: FlightEntity): SealStatus? {
        val signoffId = flight.witnessSignoffId ?: return null
        val seal = flight.witnessSeal ?: return SealStatus.CANNOT_VERIFY
        val signoff = dao.get(signoffId) ?: return SealStatus.CANNOT_VERIFY
        return WitnessSeal.check(sealInput(flight, signoff), seal, signoff.sealVersion)
    }

    /** Every witnessed live flight's status, by flight ID, for the list marks and the Witness filter. */
    suspend fun statuses(): Map<String, SealStatus> = statusesOf(dao.witnessedFlights(), dao.live())

    /** The same, kept up to date as flights and sign-offs change. */
    fun observeStatuses(): Flow<Map<String, SealStatus>> =
        combine(dao.observeWitnessedFlights(), dao.observeLive()) { f, s -> statusesOf(f, s) }

    fun observeSignoffFor(flightId: String): Flow<WitnessSignoffEntity?> = dao.observeForFlight(flightId)

    /** The flights a sign-off screen offers: one flight, or every live flight on a day. */
    suspend fun candidates(flightId: String?, date: String?): List<FlightEntity> = when {
        flightId != null -> listOfNotNull(flights.get(flightId)?.takeIf { it.deletedAt == null })
        date != null -> flights.onDate(date)
        else -> emptyList()
    }

    /** The flights whose seal no longer matches, for the Changed since witnessed filter. */
    suspend fun changedIds(): Set<String> = statuses().filterValues { it == SealStatus.CHANGED }.keys

    private fun statusesOf(flights: List<FlightEntity>, signoffs: List<WitnessSignoffEntity>): Map<String, SealStatus> {
        val byId = signoffs.associateBy { it.id }
        return flights.associate { f ->
            val s = f.witnessSignoffId?.let { byId[it] }
            val seal = f.witnessSeal
            f.id to if (s == null || seal == null) SealStatus.CANNOT_VERIFY else WitnessSeal.check(sealInput(f, s), seal, s.sealVersion)
        }
    }

    companion object {
        /** A stored photo is about 10 to 20 KB; anything over this is refused. */
        const val MAX_PHOTO_BYTES = 100 * 1024

        /** The flight's signed fields and the sign-off's details, as the seal covers them. */
        fun sealInput(f: FlightEntity, s: WitnessSignoffEntity) = SealInput(
            logNo = f.logNo,
            date = LocalDate.parse(f.date),
            durationMinutes = f.durationMinutes,
            gliderId = f.gliderId,
            airfieldId = f.airfieldId,
            launchTypeId = f.launchTypeId,
            role = f.role,
            picName = f.picName,
            launchTime = f.launchTime,
            landingTime = f.landingTime,
            landingAirfieldId = f.landingAirfieldId,
            landingPlace = f.landingPlace,
            witnessName = s.witnessName,
            witnessIdentifier = s.witnessIdentifier,
            signedAt = s.signedAt,
            photoSha256 = s.photoSha256,
        )
    }
}
