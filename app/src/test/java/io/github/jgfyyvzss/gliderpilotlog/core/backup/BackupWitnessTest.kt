package io.github.jgfyyvzss.gliderpilotlog.core.backup

import io.github.jgfyyvzss.gliderpilotlog.core.MsgKey
import io.github.jgfyyvzss.gliderpilotlog.core.backup.BackupTestData.T1
import io.github.jgfyyvzss.gliderpilotlog.core.backup.BackupTestData.T2
import io.github.jgfyyvzss.gliderpilotlog.core.witness.SealInput
import io.github.jgfyyvzss.gliderpilotlog.core.witness.SealStatus
import io.github.jgfyyvzss.gliderpilotlog.core.witness.WitnessSeal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Witness sign-offs in the version 2 backup: round trip, import checks, seal summary and merge. */
class BackupWitnessTest {
    /** Starts like a JPEG; the import checks only the marker, the size and the hash. */
    private val photo = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(2_000) { it.toByte() }
    private val photoText = BackupWitness.encodePhoto(photo)
    private val hash = WitnessSeal.sha256Hex(photo)

    private fun signoff(id: String = "s1", photo: String? = photoText, sha: String? = hash, updatedAt: String = T1, removed: Boolean = false) =
        BackupWitnessSignoff(
            id = id, witnessName = "Lee Instructor", witnessIdentifier = "GFA 1", signedAt = T1, photo = photo, photoSha256 = sha,
            photoRemoved = removed, sealVersion = WitnessSeal.VERSION, consentConfirmed = true, updatedAt = updatedAt,
        )

    /** The test file with f1 and f2 witnessed by [s] and correctly sealed. */
    private fun witnessed(s: BackupWitnessSignoff = signoff()): BackupFile {
        val base = BackupTestData.file()
        val flights = base.flights.map { f -> if (f.id == "f0") f else f.copy(witnessSignoffId = s.id, witnessSeal = sealFor(f, s)) }
        return base.copy(flights = flights, witnessSignoffs = listOf(s))
    }

    /** The seal the app would have stored when signing, from the flight's fields and the sign-off. */
    private fun sealFor(f: BackupFlight, s: BackupWitnessSignoff): String {
        val input = SealInput(
            f.logNo, LocalDate.parse(f.date), f.durationMinutes, f.gliderId, f.airfieldId, f.launchTypeId, f.role,
            f.picName, f.launchTime, f.landingTime, f.landingAirfieldId, f.landingPlace, s.witnessName, s.witnessIdentifier,
            BackupTime.fromIso(s.signedAt)!!, s.photoSha256,
        )
        return WitnessSeal.seal(input)
    }

    private fun keys(r: WitnessReview) = r.summary.notes.map { it.key }

    @Test fun witnessDataRoundTripsUnchanged() {
        val first = BackupCodec.encode(witnessed())
        val decoded = (BackupCodec.decode(first) as DecodeResult.Ok).file
        assertEquals(first, BackupCodec.encode(decoded))
        assertEquals(photoText, decoded.witnessSignoffs.single().photo)
        assertTrue(first.contains("\"witness_signoff_id\": \"s1\""))
        assertTrue(BackupValidation.validate(decoded).isEmpty())
    }

    @Test fun sealsAreCountedForLiveFlights() {
        val r = BackupWitness.review(witnessed())
        assertEquals(WitnessSummary(verified = 2, changed = 0, cannotVerify = 0, notes = emptyList()), r.summary)

        val edited = witnessed().let { f -> f.copy(flights = f.flights.map { if (it.id == "f2") it.copy(durationMinutes = 300) else it }) }
        val e = BackupWitness.review(edited).summary
        assertEquals(1, e.verified)
        assertEquals("a mismatch never blocks the import", 1, e.changed)
        assertTrue(BackupValidation.validate(edited).isEmpty())

        val future = witnessed(signoff().copy(sealVersion = 9))
        assertEquals(2, BackupWitness.review(future).summary.cannotVerify)
    }

    @Test fun badPhotosAreDroppedAndTheSignoffKept() {
        listOf(
            signoff(photo = "not base64!") to MsgKey.BK_PHOTO_NOT_BASE64,
            signoff(photo = BackupWitness.encodePhoto(ByteArray(100) { 1 })) to MsgKey.BK_PHOTO_NOT_JPEG,
            signoff(photo = BackupWitness.encodePhoto(photo + ByteArray(BackupWitness.MAX_PHOTO_BYTES))) to MsgKey.BK_PHOTO_TOO_LARGE,
            signoff(sha = hash.reversed()) to MsgKey.BK_PHOTO_HASH,
        ).forEach { (s, key) ->
            val r = BackupWitness.review(witnessed(s))
            assertEquals(key.name, listOf(key), keys(r))
            val kept = r.file.witnessSignoffs.single()
            assertNull(kept.photo)
            assertEquals("the hash stays", s.photoSha256, kept.photoSha256)
            assertEquals(2, r.file.flights.count { it.witnessSignoffId == "s1" })
        }
    }

    @Test fun aPhotoLeftOutIsNotAProblem() {
        val r = BackupWitness.review(witnessed(signoff(photo = null)))
        assertTrue(r.summary.notes.isEmpty())
        assertEquals(2, r.summary.verified)
    }

    @Test fun danglingReferencesLoseTheWitnessMark() {
        val base = witnessed()
        val missing = base.copy(witnessSignoffs = emptyList())
        val r = BackupWitness.review(missing)
        assertEquals(listOf(MsgKey.BK_WITNESS_DANGLING, MsgKey.BK_WITNESS_DANGLING), keys(r))
        assertTrue(r.file.flights.all { it.witnessSignoffId == null && it.witnessSeal == null })
        assertEquals("the flights keep their data", base.flights.map { it.durationMinutes }, r.file.flights.map { it.durationMinutes })

        val halfSet = base.copy(flights = base.flights.map { if (it.id == "f1") it.copy(witnessSeal = null) else it })
        val h = BackupWitness.review(halfSet)
        assertEquals(listOf(MsgKey.BK_WITNESS_DANGLING), keys(h))
        assertNull(h.file.flights.first { it.id == "f1" }.witnessSignoffId)

        val deletedSignoff = witnessed(signoff().copy(deletedAt = T2))
        assertEquals(2, keys(BackupWitness.review(deletedSignoff)).size)

        val deletedFlight = BackupTestData.file().let { f ->
            f.copy(flights = f.flights.map { if (it.id == "f0") it.copy(witnessSignoffId = "gone", witnessSeal = "x") else it })
        }
        val d = BackupWitness.review(deletedFlight)
        assertTrue("a deleted flight is cleared quietly", d.summary.notes.isEmpty())
        assertNull(d.file.flights.first { it.id == "f0" }.witnessSignoffId)
    }

    @Test fun signoffsNeedANameConsentAndReadableTimes() {
        val bad = witnessed(signoff().copy(witnessName = " ", consentConfirmed = false, signedAt = "yesterday"))
        val problems = BackupValidation.validate(bad).map { it.key }
        assertTrue(MsgKey.BK_NO_NAME in problems)
        assertTrue(MsgKey.BK_WITNESS_NO_CONSENT in problems)
        assertTrue(MsgKey.BK_BAD_TIME in problems)

        val twice = witnessed().let { it.copy(witnessSignoffs = it.witnessSignoffs + it.witnessSignoffs) }
        assertTrue(MsgKey.BK_DUPLICATE_ID in BackupValidation.validate(twice).map { it.key })
    }

    @Test fun mergeKeepsAPhotoTheOtherFileLeftOut() {
        val local = witnessed()
        val noPhotos = witnessed(signoff(photo = null, updatedAt = T2))
        val merged = BackupMerge.merge(local, noPhotos).merged.witnessSignoffs.single()
        assertEquals("the newer side wins but keeps the photo", T2, merged.updatedAt)
        assertEquals(photoText, merged.photo)

        val filled = BackupMerge.merge(witnessed(signoff(photo = null)), local).merged.witnessSignoffs.single()
        assertEquals("a full backup fills a missing photo", photoText, filled.photo)
    }

    @Test fun mergeHonoursARemovedPhoto() {
        val removed = witnessed(signoff(photo = null, updatedAt = T2, removed = true))
        val merged = BackupMerge.merge(witnessed(), removed).merged.witnessSignoffs.single()
        assertNull(merged.photo)
        assertTrue(merged.photoRemoved)
        assertNotNull(merged.photoSha256)
    }

    @Test fun aNewerEditedFlightBringsItsSealAndShowsChanged() {
        val local = witnessed()
        val theirs = local.copy(flights = local.flights.map { if (it.id == "f1") it.copy(durationMinutes = 200, updatedAt = T2) else it })
        val merged = BackupMerge.merge(local, theirs).merged
        val f1 = merged.flights.first { it.id == "f1" }
        assertEquals(200, f1.durationMinutes)
        assertEquals(SealStatus.CHANGED, BackupWitness.status(f1, merged.witnessSignoffs.single()))
    }
}
