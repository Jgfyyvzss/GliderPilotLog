package io.github.jgfyyvzss.soarlog.data

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jgfyyvzss.soarlog.core.Keys
import io.github.jgfyyvzss.soarlog.core.Role
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The Room constraints from Appendix A: partial unique indexes, foreign keys, triggers and seeds. */
@RunWith(AndroidJUnit4::class)
class SchemaConstraintsTest {
    private lateinit var db: LogbookDatabase
    private val aero = Keys.seededLaunchTypeId("Aero")

    @Before fun open() {
        db = LogbookDatabase.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @After fun close() = db.close()

    private suspend fun glider(reg: String = "FQM", deletedAt: Long? = null) =
        GliderEntity(id = Keys.newId(), registration = reg, registrationKey = Keys.registrationKey(reg), type = "Hornet",
            incomplete = false, updatedAt = 1, deletedAt = deletedAt).also { db.gliderDao().insert(it) }

    private suspend fun airfield(name: String = "YMBT", deletedAt: Long? = null) =
        AirfieldEntity(id = Keys.newId(), name = name, nameKey = Keys.nameKey(name), incomplete = true,
            updatedAt = 1, deletedAt = deletedAt).also { db.airfieldDao().insert(it) }

    private fun flight(g: GliderEntity, a: AirfieldEntity, logNo: Int = 1, deletedAt: Long? = null) = FlightEntity(
        id = Keys.newId(), logNo = logNo, date = "2026-10-17", durationMinutes = 60, gliderId = g.id,
        airfieldId = a.id, launchTypeId = aero, role = Role.PIC.name, picName = "Pat", source = FlightSource.MANUAL.name,
        updatedAt = 1, deletedAt = deletedAt,
    )

    private suspend inline fun assertRejected(crossinline block: suspend () -> Unit) {
        try {
            block()
            fail("expected the database to reject this")
        } catch (e: SQLiteException) {
            // SQLiteConstraintException for indexes and foreign keys, a plain SQLiteException for trigger aborts.
        }
    }

    @Test fun seedsLaunchTypesAndOpeningBalances() = runTest {
        val types = db.launchTypeDao().observeAll().first()
        assertEquals(listOf("Aero", "Winch", "Self", "Auto"), types.map { it.name })
        assertEquals(Keys.seededLaunchTypes.map(Keys::seededLaunchTypeId), types.map { it.id })
        val c = db.query("SELECT COUNT(*), SUM(minutes), SUM(flights) FROM opening_balance", null)
        c.moveToFirst()
        assertEquals(5, c.getInt(0))
        assertEquals(0, c.getInt(1))
        assertEquals(0, c.getInt(2))
        c.close()
    }

    @Test fun liveLogNoIsUnique() = runTest {
        val g = glider(); val a = airfield()
        db.flightDao().insert(flight(g, a, logNo = 7))
        assertRejected { db.flightDao().insert(flight(g, a, logNo = 7)) }
    }

    @Test fun tombstonedLogNoCanBeReused() = runTest {
        val g = glider(); val a = airfield()
        db.flightDao().insert(flight(g, a, logNo = 7, deletedAt = 5))
        db.flightDao().insert(flight(g, a, logNo = 7))
        db.flightDao().insert(flight(g, a, logNo = 8, deletedAt = 5))
        assertEquals(7, db.flightDao().maxLogNo())
    }

    @Test fun maxLogNoIsNullWithNoLiveFlights() = runTest {
        assertNull(db.flightDao().maxLogNo())
    }

    @Test fun liveRegistrationKeyIsUnique() = runTest {
        glider("VH-FQM")
        assertRejected { glider("vh fqm") }
        glider("VHFQM", deletedAt = 3) // a tombstone does not collide
    }

    @Test fun liveNameKeysAreUnique() = runTest {
        airfield("Mt Beauty")
        assertRejected { airfield("mt beauty") }
        airfield("MT BEAUTY", deletedAt = 3)

        db.clubDao().insert(ClubEntity(Keys.newId(), "MBGC", "mbgc", 1))
        assertRejected { db.clubDao().insert(ClubEntity(Keys.newId(), "mbgc", "mbgc", 1)) }

        assertRejected { db.launchTypeDao().insert(LaunchTypeEntity(Keys.newId(), "Winch", "winch", 9, updatedAt = 1)) }
    }

    @Test fun foreignKeysAreEnforced() = runTest {
        val g = glider(); val a = airfield()
        try {
            db.flightDao().insert(flight(g, a).copy(gliderId = "missing"))
            fail("expected a foreign key failure")
        } catch (e: SQLiteConstraintException) {
        }
        db.flightDao().insert(flight(g, a))
        try {
            db.openHelper.writableDatabase.execSQL("DELETE FROM glider WHERE id = ?", arrayOf(g.id))
            fail("expected deletion to be restricted")
        } catch (e: SQLiteConstraintException) {
        }
    }

    @Test fun triggersEnforceFlightRules() = runTest {
        val g = glider(); val a = airfield()
        val ok = flight(g, a, logNo = 1)
        assertRejected { db.flightDao().insert(ok.copy(id = Keys.newId(), logNo = 0)) }
        assertRejected { db.flightDao().insert(ok.copy(id = Keys.newId(), durationMinutes = 0)) }
        assertRejected { db.flightDao().insert(ok.copy(id = Keys.newId(), durationMinutes = 1441)) }
        assertRejected { db.flightDao().insert(ok.copy(id = Keys.newId(), picName = "  ")) }
        assertRejected { db.flightDao().insert(ok.copy(id = Keys.newId(), role = "DUAL")) }
        assertRejected { db.flightDao().insert(ok.copy(id = Keys.newId(), landingAirfieldId = a.id, landingPlace = "Paddock")) }
        assertRejected { db.flightDao().insert(ok.copy(id = Keys.newId(), launchTime = "13:00", landingTime = "12:59")) }

        db.flightDao().insert(ok)
        assertRejected { db.flightDao().update(ok.copy(durationMinutes = -5)) }
        db.flightDao().update(ok.copy(launchTime = "13:00", landingTime = "14:00", landingPlace = "Paddock"))
    }

    @Test fun repositoryReportsDuplicateLogNoHolder() = runTest {
        val repo = LogbookRepository(db)
        val g = glider(); val a = airfield()
        db.flightDao().insert(flight(g, a, logNo = 340))
        assertEquals(341, repo.nextLogNo())
        val holder = db.flightDao().liveHolderOf(340)
        assertEquals("FQM", holder?.registration)
        assertNull(db.flightDao().liveHolderOf(341))
    }

    @Test fun deletingTheHighestFlightFreesItsNumber() = runTest {
        val repo = LogbookRepository(db)
        val g = glider(); val a = airfield()
        val f = flight(g, a, logNo = 12)
        db.flightDao().insert(flight(g, a, logNo = 11))
        db.flightDao().insert(f)
        repo.deleteFlight(f.id)
        assertEquals(12, repo.nextLogNo())
        assertTrue(db.flightDao().get(f.id)?.deletedAt != null)
    }
}
