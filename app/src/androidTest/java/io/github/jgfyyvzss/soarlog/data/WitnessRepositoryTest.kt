package io.github.jgfyyvzss.soarlog.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jgfyyvzss.soarlog.core.FlightDraft
import io.github.jgfyyvzss.soarlog.core.Keys
import io.github.jgfyyvzss.soarlog.core.Role
import io.github.jgfyyvzss.soarlog.core.witness.SealStatus
import io.github.jgfyyvzss.soarlog.core.witness.WitnessSeal
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Witness sign-off in the database: batches, seals, edits, photo removal and clean-up. Names are made up. */
@RunWith(AndroidJUnit4::class)
class WitnessRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: LogbookDatabase
    private lateinit var repo: LogbookRepository
    private lateinit var witness: WitnessRepository
    private var clock = 1_760_000_000_000L
    private lateinit var draft: FlightDraft

    @Before fun open() = runTest {
        db = LogbookDatabase.inMemory(context)
        repo = LogbookRepository(db, now = { ++clock })
        witness = WitnessRepository(db, now = { ++clock })
        val g = (repo.saveGlider(null, "FQM", "Hornet", archived = false) as SaveResult.Saved).id
        val a = (repo.saveAirfield(null, "YMBT", null, null, null, null, emptyList(), archived = false) as SaveResult.Saved).id
        draft = FlightDraft(
            logNoText = "1", date = LocalDate.of(2026, 10, 17), durationText = "30",
            gliderId = g, airfieldId = a, launchTypeId = Keys.seededLaunchTypeId("Winch"), role = Role.STUDENT, picName = "Chris",
        )
    }

    @After fun close() = db.close()

    private suspend fun flight(logNo: Int, notes: String = ""): String =
        repo.saveFlight(null, draft.copy(logNoText = logNo.toString(), notes = notes)).second!!

    private val photo = ByteArray(15_000) { it.toByte() }

    @Test fun oneSignoffSealsEveryTickedFlight() = runTest {
        val ids = listOf(flight(1), flight(2), flight(3))
        val r = witness.sign(ids.take(2), " Chris ", " ", photo, consent = true) as SignResult.Signed
        val s = witness.signoff(r.signoffId)!!
        assertEquals("Chris", s.witnessName)
        assertNull("a blank number is stored as none", s.witnessIdentifier)
        assertEquals(WitnessSeal.sha256Hex(photo), s.photoSha256)
        assertArrayEquals(photo, s.photo)
        assertEquals(WitnessSeal.VERSION, s.sealVersion)
        assertTrue(s.consentConfirmed)

        val statuses = witness.statuses()
        assertEquals(mapOf(ids[0] to SealStatus.WITNESSED, ids[1] to SealStatus.WITNESSED), statuses)
        assertNull(witness.status(repo.flight(ids[2])!!))
        assertEquals(listOf(WitnessPick("Chris", null)), witness.observePicks().first())
    }

    @Test fun signingIsRefusedWithoutNameConsentFlightsOrPhoto() = runTest {
        val id = flight(1)
        assertEquals(SignResult.Refused(SignRefusal.NO_NAME), witness.sign(listOf(id), " ", null, photo, consent = true))
        assertEquals(SignResult.Refused(SignRefusal.NO_CONSENT), witness.sign(listOf(id), "Chris", null, photo, consent = false))
        assertEquals(SignResult.Refused(SignRefusal.NO_FLIGHTS), witness.sign(listOf("missing"), "Chris", null, photo, consent = true))
        assertEquals(SignResult.Refused(SignRefusal.NO_PHOTO), witness.sign(listOf(id), "Chris", null, ByteArray(0), consent = true))
        assertEquals(
            SignResult.Refused(SignRefusal.PHOTO_TOO_LARGE),
            witness.sign(listOf(id), "Chris", null, ByteArray(WitnessRepository.MAX_PHOTO_BYTES + 1), consent = true),
        )
        assertNull(repo.flight(id)!!.witnessSignoffId)
    }

    @Test fun editingASignedFieldShowsButNotesDoNot() = runTest {
        val id = flight(1)
        witness.sign(listOf(id), "Chris", "12345", photo, consent = true)
        val witnessed = repo.flight(id)!!

        // Saving the flight again keeps the mark; changing only the notes keeps the seal.
        repo.saveFlight(id, draft.copy(notes = "Good thermals"))
        assertEquals(witnessed.witnessSignoffId, repo.flight(id)!!.witnessSignoffId)
        assertEquals(SealStatus.WITNESSED, witness.status(repo.flight(id)!!))

        repo.saveFlight(id, draft.copy(durationText = "31"))
        assertEquals(SealStatus.CHANGED, witness.status(repo.flight(id)!!))
        assertEquals(SealStatus.CHANGED, witness.statuses()[id])
    }

    @Test fun removingThePhotoKeepsTheSeals() = runTest {
        val id = flight(1)
        val signed = witness.sign(listOf(id), "Chris", null, photo, consent = true) as SignResult.Signed
        witness.removePhoto(signed.signoffId)
        val s = witness.signoff(signed.signoffId)!!
        assertNull(s.photo)
        assertTrue(s.photoRemoved)
        assertNotNull("the hash stays", s.photoSha256)
        assertEquals(SealStatus.WITNESSED, witness.status(repo.flight(id)!!))
    }

    @Test fun unreferencedSignoffsAreDeletedWithTheirPhotos() = runTest {
        val a = flight(1)
        val b = flight(2)
        val signed = witness.sign(listOf(a, b), "Chris", null, photo, consent = true) as SignResult.Signed

        witness.removeMark(a)
        assertNull(repo.flight(a)!!.witnessSignoffId)
        assertNull(repo.flight(a)!!.witnessSeal)
        assertNull("still used by the other flight", witness.signoff(signed.signoffId)!!.deletedAt)

        repo.deleteFlight(b)
        val gone = witness.signoff(signed.signoffId)!!
        assertNotNull("deleted once no live flight uses it", gone.deletedAt)
        assertNull(gone.photo)
        assertTrue(witness.observePicks().first().isEmpty())
    }

    @Test fun witnessingAgainReplacesTheOldSignoff() = runTest {
        val id = flight(1)
        val first = witness.sign(listOf(id), "Chris", null, photo, consent = true) as SignResult.Signed
        repo.saveFlight(id, draft.copy(durationText = "31"))
        assertEquals(SealStatus.CHANGED, witness.status(repo.flight(id)!!))

        val second = witness.sign(listOf(id), "Dana", null, photo, consent = true) as SignResult.Signed
        assertEquals(second.signoffId, repo.flight(id)!!.witnessSignoffId)
        assertEquals(SealStatus.WITNESSED, witness.status(repo.flight(id)!!))
        assertNotNull(witness.signoff(first.signoffId)!!.deletedAt)
    }
}
