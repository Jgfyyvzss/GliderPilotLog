package io.github.jgfyyvzss.soarlog.core.backup

import io.github.jgfyyvzss.soarlog.core.Msg
import io.github.jgfyyvzss.soarlog.core.MsgKey
import io.github.jgfyyvzss.soarlog.core.msg
import io.github.jgfyyvzss.soarlog.core.witness.SealInput
import io.github.jgfyyvzss.soarlog.core.witness.SealStatus
import io.github.jgfyyvzss.soarlog.core.witness.WitnessSeal
import java.time.LocalDate
import java.util.Base64

/** The witness part of an import summary: how the live witnessed flights' seals compare, and what was repaired. */
data class WitnessSummary(val verified: Int, val changed: Int, val cannotVerify: Int, val notes: List<Msg>) {
    val isEmpty get() = verified == 0 && changed == 0 && cannotVerify == 0 && notes.isEmpty()
}

data class WitnessReview(val file: BackupFile, val summary: WitnessSummary)

/**
 * Witness data on import (docs/Witness_Sign-Off.md). None of it blocks an import: a bad photo is dropped and the
 * sign-off kept, a flight whose sign-off is missing keeps its data but loses its witness mark, and a seal that no
 * longer matches simply shows as changed.
 */
object BackupWitness {
    /** The largest photo accepted, as for a new sign-off. */
    const val MAX_PHOTO_BYTES = 100 * 1024

    /** Standard base64 with padding and no line breaks, so the same bytes always give the same text. */
    fun encodePhoto(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    fun decodePhoto(text: String): ByteArray? = runCatching { Base64.getDecoder().decode(text) }.getOrNull()

    /** A JPEG starts with the SOI marker FF D8 followed by another marker. */
    fun isJpeg(bytes: ByteArray): Boolean =
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()

    fun review(file: BackupFile): WitnessReview {
        val notes = mutableListOf<Msg>()
        val signoffs = file.witnessSignoffs.map { s ->
            val problem = s.photo?.let { photoProblem(it, s.photoSha256) } ?: return@map s
            notes += msg(problem, msg(MsgKey.BK_WITNESS, s.witnessName))
            s.copy(photo = null)
        }
        val byId = signoffs.associateBy { it.id }
        var verified = 0
        var changed = 0
        var cannotVerify = 0
        val flights = file.flights.map { f ->
            if (f.witnessSignoffId == null && f.witnessSeal == null) return@map f
            val s = f.witnessSignoffId?.let { byId[it] }
            // A live flight needs a live sign-off; a deleted flight may keep one that was deleted with it.
            if (s == null || f.witnessSeal == null || (f.deletedAt == null && s.deletedAt != null)) {
                if (f.deletedAt == null) notes += msg(MsgKey.BK_WITNESS_DANGLING, msg(MsgKey.BK_FLIGHT, f.logNo))
                return@map f.copy(witnessSignoffId = null, witnessSeal = null)
            }
            if (f.deletedAt == null) {
                when (status(f, s)) {
                    SealStatus.WITNESSED -> verified++
                    SealStatus.CHANGED -> changed++
                    SealStatus.CANNOT_VERIFY -> cannotVerify++
                }
            }
            f
        }
        return WitnessReview(file.copy(witnessSignoffs = signoffs, flights = flights), WitnessSummary(verified, changed, cannotVerify, notes))
    }

    /** Why a photo cannot be kept, or null when it is a JPEG of at most [MAX_PHOTO_BYTES] matching its hash. */
    private fun photoProblem(text: String, sha256: String?): MsgKey? {
        val bytes = decodePhoto(text) ?: return MsgKey.BK_PHOTO_NOT_BASE64
        return when {
            !isJpeg(bytes) -> MsgKey.BK_PHOTO_NOT_JPEG
            bytes.size > MAX_PHOTO_BYTES -> MsgKey.BK_PHOTO_TOO_LARGE
            sha256 == null || !WitnessSeal.sha256Hex(bytes).equals(sha256, ignoreCase = true) -> MsgKey.BK_PHOTO_HASH
            else -> null
        }
    }

    /** The flight's seal checked against its fields as they are in the file. */
    fun status(f: BackupFlight, s: BackupWitnessSignoff): SealStatus {
        val seal = f.witnessSeal ?: return SealStatus.CANNOT_VERIFY
        val signedAt = BackupTime.fromIso(s.signedAt) ?: return SealStatus.CANNOT_VERIFY
        val date = runCatching { LocalDate.parse(f.date) }.getOrNull() ?: return SealStatus.CANNOT_VERIFY
        val input = SealInput(
            logNo = f.logNo, date = date, durationMinutes = f.durationMinutes, gliderId = f.gliderId, airfieldId = f.airfieldId,
            launchTypeId = f.launchTypeId, role = f.role, picName = f.picName, launchTime = f.launchTime, landingTime = f.landingTime,
            landingAirfieldId = f.landingAirfieldId, landingPlace = f.landingPlace, witnessName = s.witnessName,
            witnessIdentifier = s.witnessIdentifier, signedAt = signedAt, photoSha256 = s.photoSha256,
        )
        return WitnessSeal.check(input, seal, s.sealVersion)
    }
}
