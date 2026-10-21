package io.github.jgfyyvzss.gliderpilotlog.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jgfyyvzss.gliderpilotlog.core.FlightDraft
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

/** Step 4 done criteria: exports re-import cleanly, stubs and use-existing work, and Undo removes the batch. */
@RunWith(AndroidJUnit4::class)
class CsvServiceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var source: LogbookDatabase
    private lateinit var target: LogbookDatabase
    private var clock = 1_760_000_000_000L
    private val winch = Keys.seededLaunchTypeId("Winch")

    @Before fun open() {
        source = LogbookDatabase.inMemory(context)
        target = LogbookDatabase.inMemory(context)
    }

    @After fun close() {
        source.close()
        target.close()
    }

    private fun csv(db: LogbookDatabase) = CsvService(db, now = { ++clock })
    private fun repo(db: LogbookDatabase) = LogbookRepository(db, now = { ++clock })
    private fun SaveResult.id() = (this as SaveResult.Saved).id

    @Test fun exportsReimportIntoAnEmptyLogbook() = runTest {
        val r = repo(source)
        val c = r.saveClub(null, "MBGC").id()
        val a = r.saveAirfield(null, "YMBT", -36.7314, 147.17, 300, 1500, listOf(c), archived = false).id()
        val g = r.saveGlider(null, "VH-FQM", "Hornet", archived = false).id()
        r.saveFlight(null, FlightDraft(logNoText = "340", date = LocalDate.of(2026, 10, 17), durationText = "431", gliderId = g,
            airfieldId = a, clubId = c, launchTypeId = winch, role = Role.PIC, picName = "Pat", launchTimeText = "1305",
            landingTimeText = "1736", notes = "Wave, \"strong\""))
        r.saveFlight(null, FlightDraft(logNoText = "341", date = LocalDate.of(2026, 10, 18), durationText = "5", gliderId = g,
            airfieldId = a, clubId = c, launchTypeId = winch, role = Role.STUDENT, picName = "Sam Instructor"))

        val out = csv(source)
        val t = csv(target)
        val gliders = t.prepareGliders(out.exportGliders())
        assertEquals(emptyList<String>(), gliders.errors.map { it.message.text(context) })
        t.commitGliders(gliders, "gliders.csv")
        val airfields = t.prepareAirfields(out.exportAirfields())
        t.commitAirfields(airfields, "airfields.csv")
        val flights = t.prepareFlights(out.exportFlights())
        assertEquals(emptyList<String>(), flights.errors.map { it.message.text(context) })
        assertTrue(flights.unmatchedGliders.isEmpty() && flights.unmatchedAirfields.isEmpty())
        t.commitFlights(flights, FlightImportChoices(), "flights.csv")

        // Re-exporting the imported logbook gives the same files.
        assertEquals(out.exportFlights(), t.exportFlights())
        assertEquals(out.exportGliders(), t.exportGliders())
        assertEquals(out.exportAirfields(), t.exportAirfields())
        val f = target.csvDao().liveFlights().first()
        assertEquals("CSV", f.source)
        assertEquals("13:05", f.launchTime)
    }

    @Test fun stubsUseExistingDefaultsAndUndo() = runTest {
        val r = repo(target)
        val existing = r.saveAirfield(null, "Mount Beauty", null, null, null, null, emptyList(), archived = false).id()
        r.saveGlider(null, "FQM", "Hornet", archived = false)
        val text = "date,glider,duration,airfield,role,pic,club\r\n" +
            "2026-10-17,FQM,60,Mt Beauty,PIC,,MBGC\r\n" +
            "2026-10-18,GZY,45,Mt Beauty,STUDENT,Sam,\r\n" +
            "2026-10-19,GZY,30,Benalla,PIC,Pat,\r\n"
        val t = csv(target)
        val imp = t.prepareFlights(text)
        assertEquals(emptyList<String>(), imp.errors.map { it.message.text(context) })
        assertEquals(3, imp.rowsNeedingLaunchType)
        assertEquals(1, imp.rowsNeedingProfileName)
        val result = t.commitFlights(
            imp,
            FlightImportChoices(
                gliders = mapOf("gzy" to null),                                       // create a stub
                airfields = mapOf("mt beauty" to existing, "benalla" to null),      // use existing, and a stub
                defaultLaunchTypeId = winch,
                profileName = "Pat Pilot",
            ),
            "memento.csv",
        )
        assertEquals(ImportOutcome(flights = 3, gliders = 1, airfields = 1, clubs = 1), result)

        val flights = target.csvDao().liveFlights()
        assertEquals(listOf(1, 2, 3), flights.map { it.logNo })
        assertEquals("Pat Pilot", flights[0].picName)
        assertTrue(flights.all { it.launchTypeId == winch })
        // The named club was created and linked; the blank club on the same airfield is then filled from it.
        val mbgc = r.observeClubs().first().single { it.name == "MBGC" }.id
        assertEquals(listOf(mbgc, mbgc, null), flights.map { it.clubId })
        val stub = r.observeGliders().first().single { it.registration == "GZY" }
        assertTrue(stub.incomplete)

        // A hand-entered flight in the stub glider keeps it through Undo.
        r.saveFlight(null, FlightDraft(logNoText = "4", date = LocalDate.of(2026, 10, 20), durationText = "100", gliderId = stub.id,
            airfieldId = existing, clubId = mbgc, launchTypeId = winch, role = Role.PIC, picName = "Pat"))
        val preview = t.undoPreview()!!
        assertEquals("memento.csv", preview.batch.fileName)
        assertEquals(3, preview.flights)
        assertEquals(0, preview.gliders)
        assertEquals(1, preview.airfields)
        t.undo(preview.batch.id)

        assertEquals(listOf(4), target.csvDao().liveFlights().map { it.logNo })
        assertTrue(r.observeGliders().first().any { it.registration == "GZY" })
        assertTrue(r.observeAirfields().first().none { it.name == "Benalla" })
        assertNull(t.undoPreview())
        // The removed flights are kept as tombstones, so a later Merge respects the removal.
        assertEquals(3, target.backupDao().allFlights().count { it.deletedAt != null })
    }
}
