package io.github.jgfyyvzss.gliderpilotlog.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jgfyyvzss.gliderpilotlog.core.FlightDraft
import io.github.jgfyyvzss.gliderpilotlog.core.FlightField
import io.github.jgfyyvzss.gliderpilotlog.core.FlightValidation
import io.github.jgfyyvzss.gliderpilotlog.core.Keys
import io.github.jgfyyvzss.gliderpilotlog.core.Role
import io.github.jgfyyvzss.gliderpilotlog.ui.text
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LogbookRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: LogbookDatabase
    private lateinit var repo: LogbookRepository

    @Before fun open() {
        db = LogbookDatabase.inMemory(context)
        repo = LogbookRepository(db)
    }

    @After fun close() = db.close()

    private fun SaveResult.id() = (this as SaveResult.Saved).id

    @Test fun gliderRegistrationMatchesIgnoringCaseSpacesAndHyphens() = runTest {
        repo.saveGlider(null, "VH-FQM", "Hornet", archived = false).id()
        val dup = repo.saveGlider(null, "vh fqm", "Hornet", archived = false)
        assertEquals("VH-FQM is already in the glider list", (dup as SaveResult.Rejected).message.text(context))
        assertTrue(repo.saveGlider(null, "?", "", archived = false) is SaveResult.Saved)
    }

    @Test fun editingAGliderKeepsItsOwnRegistration() = runTest {
        val id = repo.saveGlider(null, "FQM", "Hornet", archived = false).id()
        assertEquals(id, repo.saveGlider(id, "fqm", "Hornet", archived = true).id())
        assertTrue(repo.glider(id)!!.archived)
    }

    @Test fun airfieldClubLinksAreReplaced() = runTest {
        val c1 = repo.saveClub(null, "MBGC").id()
        val c2 = repo.saveClub(null, "Bogong").id()
        val a = repo.saveAirfield(null, "YMBT", null, null, null, null, listOf(c1, c2), archived = false).id()
        assertEquals(listOf("Bogong", "MBGC"), repo.clubsFor(a).map { it.name })
        repo.saveAirfield(a, "YMBT", -36.7, 147.17, 300, null, listOf(c1), archived = false)
        assertEquals(listOf("MBGC"), repo.clubsFor(a).map { it.name })
        val row = repo.observeAirfieldList().first().single()
        assertEquals("MBGC", row.clubNames)
        assertEquals(false, row.incomplete)
    }

    @Test fun defaultClubMustBeOneOfTheAirfieldsClubs() = runTest {
        val c1 = repo.saveClub(null, "MBGC").id()
        val c2 = repo.saveClub(null, "Bogong").id()
        val winch = Keys.seededLaunchTypeId("Winch")
        val a = repo.saveAirfield(null, "YMBT", null, null, null, null, listOf(c1, c2), archived = false,
            defaultClubId = c2, defaultLaunchTypeId = winch).id()
        assertEquals(c2, repo.airfield(a)!!.defaultClubId)
        assertEquals(winch, repo.airfield(a)!!.defaultLaunchTypeId)
        // Unlinking the default club clears it.
        repo.saveAirfield(a, "YMBT", null, null, null, null, listOf(c1), archived = false, defaultClubId = c2)
        assertNull(repo.airfield(a)!!.defaultClubId)
        assertNull(repo.airfield(a)!!.defaultLaunchTypeId)
    }

    @Test fun flightSaveEnforcesClubAndLogNoRules() = runTest {
        val g = repo.saveGlider(null, "FQM", "Hornet", archived = false).id()
        val c1 = repo.saveClub(null, "MBGC").id()
        val c2 = repo.saveClub(null, "Bogong").id()
        val a = repo.saveAirfield(null, "YMBT", null, null, null, null, listOf(c1, c2), archived = false).id()
        val draft = FlightDraft(
            logNoText = repo.nextLogNo().toString(), date = LocalDate.of(2026, 10, 17), durationText = "1:75",
            gliderId = g, airfieldId = a, launchTypeId = Keys.seededLaunchTypeId("Winch"),
            role = Role.PIC, picName = "Pat",
        )

        val noClub = repo.saveFlight(null, draft).first as FlightValidation.Invalid
        assertTrue(FlightField.CLUB in noClub.errors)

        val (ok, id) = repo.saveFlight(null, draft.copy(clubId = c1))
        assertTrue(ok is FlightValidation.Valid)
        val stored = repo.flight(id!!)!!
        assertEquals(1, stored.logNo)
        assertEquals(135, stored.durationMinutes)
        assertEquals(c1, stored.clubId)
        assertEquals("MANUAL", stored.source)

        val dup = repo.saveFlight(null, draft.copy(clubId = c1)).first as FlightValidation.Invalid
        assertTrue(dup.errors[FlightField.LOG_NO]!!.text(context).startsWith("Log number 1 is already used by the flight on "))

        // Saving the same flight again with its own number is fine.
        assertTrue(repo.saveFlight(id, draft.copy(clubId = c1, notes = "Wave")).first is FlightValidation.Valid)
        assertEquals("Wave", repo.flight(id)!!.notes)
        assertNull(repo.flight(id)!!.landingPlace)
    }
}
