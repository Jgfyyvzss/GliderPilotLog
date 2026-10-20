package io.github.jgfyyvzss.gliderpilotlog.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jgfyyvzss.gliderpilotlog.core.FlightDraft
import io.github.jgfyyvzss.gliderpilotlog.core.Keys
import io.github.jgfyyvzss.gliderpilotlog.core.LogbookCalc
import io.github.jgfyyvzss.gliderpilotlog.core.Role
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Step 5: the day's flights per glider, and saving the logbook total back to the glider. */
@RunWith(AndroidJUnit4::class)
class LogbookTotalTest {
    private lateinit var db: LogbookDatabase
    private lateinit var repo: LogbookRepository
    private val day = LocalDate.of(2026, 10, 18)

    @Before fun open() {
        db = LogbookDatabase.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
        repo = LogbookRepository(db)
    }

    @After fun close() = db.close()

    private fun SaveResult.id() = (this as SaveResult.Saved).id

    @Test fun dayFlightsAndSavingTheTotal() = runTest {
        val a = repo.saveAirfield(null, "YMBT", null, null, null, null, emptyList(), archived = false).id()
        val fqm = repo.saveGlider(null, "FQM", "Hornet", archived = false).id()
        val gzy = repo.saveGlider(null, "GZY", "Junior", archived = false).id()
        var logNo = 1
        suspend fun fly(glider: String, date: LocalDate, minutes: String, role: Role = Role.PIC) = repo.saveFlight(null, FlightDraft(
            logNoText = (logNo++).toString(), date = date, durationText = minutes, gliderId = glider, airfieldId = a,
            launchTypeId = Keys.seededLaunchTypeId("Winch"), role = role, picName = "Pat",
        )).second!!
        fly(fqm, day, "0:45")
        fly(gzy, day, "2:00", Role.STUDENT)
        val deleted = fly(fqm, day, "1:00")
        fly(fqm, day, "1:40", Role.INSTRUCTOR)
        fly(fqm, day.plusDays(1), "0:30")
        repo.deleteFlight(deleted)

        assertEquals(listOf("FQM", "GZY"), repo.glidersOnDay(day).map { it.registration })
        val fqmDay = repo.flightsOnDay(fqm, day)
        // Every role counts toward airframe time; the deleted flight does not.
        assertEquals(listOf(45, 100), fqmDay.map { it.durationMinutes })

        val calc = LogbookCalc(fqmDay.sumOf { it.durationMinutes }, fqmDay.size, beforeMinutes = 1234 * 60 + 56, beforeFlights = 5678)
        assertTrue(repo.saveLogbookTotal(fqm, calc.newMinutes, calc.newFlights, day) is SaveResult.Saved)
        val g = repo.glider(fqm)!!
        // 1234:56 + 0:45 + 1:40 = 1237:21
        assertEquals(1237 * 60 + 21, g.logbookTotalMinutes)
        assertEquals(5680, g.logbookTotalFlights)
        assertEquals("2026-10-18", g.logbookTotalDate)

        // Editing the glider keeps the stored total.
        repo.saveGlider(fqm, "FQM", "Hornet C", archived = false)
        assertEquals(1237 * 60 + 21, repo.glider(fqm)!!.logbookTotalMinutes)

        // Hours need a date, and clearing removes all three.
        assertTrue(repo.saveLogbookTotal(fqm, 100, null, null) is SaveResult.Rejected)
        repo.saveLogbookTotal(fqm, null, 5, day)
        repo.glider(fqm)!!.let {
            assertNull(it.logbookTotalMinutes)
            assertNull(it.logbookTotalFlights)
            assertNull(it.logbookTotalDate)
        }
    }
}
