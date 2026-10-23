package io.github.jgfyyvzss.soarlog.data

import androidx.paging.PagingSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jgfyyvzss.soarlog.core.ClockTime
import io.github.jgfyyvzss.soarlog.core.FlightFilter
import io.github.jgfyyvzss.soarlog.core.Keys
import io.github.jgfyyvzss.soarlog.core.MsgKey
import io.github.jgfyyvzss.soarlog.core.Role
import io.github.jgfyyvzss.soarlog.core.RoleTotal
import io.github.jgfyyvzss.soarlog.core.msg
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Totals and the filtered list from SQL, checked against hand-calculated figures. */
@RunWith(AndroidJUnit4::class)
class TotalsQueryTest {
    private lateinit var db: LogbookDatabase
    private lateinit var repo: LogbookRepository
    private val winch = Keys.seededLaunchTypeId("Winch")

    @Before fun open() = runTest {
        db = LogbookDatabase.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
        repo = LogbookRepository(db)
        listOf("g1" to "FQM", "g2" to "GZY").forEach { (id, reg) ->
            db.gliderDao().insert(GliderEntity(id, reg, Keys.registrationKey(reg), "Hornet", incomplete = false, updatedAt = 1))
        }
        listOf("a1" to "YMBT", "a2" to "Benalla").forEach { (id, name) ->
            db.airfieldDao().insert(AirfieldEntity(id, name, Keys.nameKey(name), incomplete = true, updatedAt = 1))
        }
        // log no, date, minutes, glider, airfield, role, launch time
        listOf(
            Flight(1, "2026-01-10", 60, "g1", "a1", Role.PIC, "10:00"),
            Flight(2, "2026-01-10", 45, "g1", "a1", Role.STUDENT, "11:30"),
            Flight(3, "2026-02-01", 120, "g2", "a2", Role.PIC, null),
            Flight(4, "2026-03-05", 30, "g2", "a1", Role.INSTRUCTOR, "14:00"),
            Flight(5, "2026-03-05", 15, "g1", "a2", Role.COACH, "15:00"),
        ).forEach { db.flightDao().insert(it.entity()) }
        // A deleted flight never counts.
        db.flightDao().insert(Flight(6, "2026-03-06", 500, "g1", "a1", Role.PIC, null).entity().copy(deletedAt = 9))
    }

    @After fun close() = db.close()

    private data class Flight(val logNo: Int, val date: String, val minutes: Int, val glider: String, val airfield: String, val role: Role, val launch: String?) {
        fun entity() = FlightEntity(
            id = "f$logNo", logNo = logNo, date = date, durationMinutes = minutes, gliderId = glider, airfieldId = airfield,
            launchTypeId = Keys.seededLaunchTypeId("Winch"), role = role.name, picName = "Pat", launchTime = launch,
            landingTime = launch?.let { ClockTime.parse(it)!!.minuteOfDay + minutes }?.let { ClockTime(it).toString() },
            source = FlightSource.MANUAL.name, updatedAt = 1,
        )
    }

    private suspend fun totals(filter: FlightFilter) = repo.observeTotalsByRole(filter).first()

    private suspend fun listedLogNos(filter: FlightFilter): List<Int> {
        val result = repo.pagedFlights(filter).load(PagingSource.LoadParams.Refresh(null, 100, false))
        return (result as PagingSource.LoadResult.Page).data.map { it.logNo }
    }

    @Test fun unfilteredTotalsByRole() = runTest {
        assertEquals(
            mapOf(
                Role.PIC to RoleTotal(180, 2),
                Role.STUDENT to RoleTotal(45, 1),
                Role.INSTRUCTOR to RoleTotal(30, 1),
                Role.COACH to RoleTotal(15, 1),
            ),
            totals(FlightFilter()),
        )
        assertEquals(listOf(5, 4, 3, 2, 1), listedLogNos(FlightFilter()))
    }

    @Test fun filtersAloneAndCombined() = runTest {
        assertEquals(mapOf(Role.PIC to RoleTotal(60, 1), Role.STUDENT to RoleTotal(45, 1), Role.COACH to RoleTotal(15, 1)),
            totals(FlightFilter(gliderIds = setOf("g1"))))
        assertEquals(listOf(4, 2, 1), listedLogNos(FlightFilter(airfieldIds = setOf("a1"))))
        assertEquals(listOf(3, 2), listedLogNos(FlightFilter(dateFrom = LocalDate.of(2026, 1, 10), dateTo = LocalDate.of(2026, 2, 1)).copy(logNoFrom = 2)))
        assertEquals(listOf(3, 1), listedLogNos(FlightFilter(roles = setOf(Role.PIC))))
        assertEquals(listOf(4, 3), listedLogNos(FlightFilter(logNoFrom = 3, logNoTo = 4)))
        // A launch time range leaves out flights without a launch time.
        assertEquals(listOf(4, 2), listedLogNos(FlightFilter(launchFrom = ClockTime.parse("11:00"), launchTo = ClockTime.parse("14:00"))))
        // Glider and airfield together, as in "my hours in this glider at this airfield".
        assertEquals(mapOf(Role.PIC to RoleTotal(60, 1), Role.STUDENT to RoleTotal(45, 1)),
            totals(FlightFilter(gliderIds = setOf("g1"), airfieldIds = setOf("a1"))))
        assertEquals(emptyMap<Role, RoleTotal>(), totals(FlightFilter(gliderIds = setOf("g2"), roles = setOf(Role.STUDENT))))
    }

    @Test fun totalsServiceAddsOpeningBalancesOnlyWithoutAFilter() = runTest {
        val balances = repo.observeOpeningBalances().first().map {
            if (it.role == Role.PIC.name) it.copy(minutes = 6000, flights = 300, asOfDate = "2025-12-31") else it
        }
        repo.saveOpeningBalances(balances)
        val settings = SettingsRepository(InstrumentationRegistry.getInstrumentation().targetContext)
        settings.setInstructionCountsAsPic(false)
        val service = TotalsService(repo, settings)

        val all = service.observe(FlightFilter()).first()
        assertEquals(RoleTotal(6180, 302), all.rows[Role.PIC])
        assertEquals(RoleTotal(6270, 305), all.combined)

        val filtered = service.observe(FlightFilter(airfieldIds = setOf("a2"))).first()
        assertEquals(RoleTotal(120, 1), filtered.rows[Role.PIC])
        assertEquals(RoleTotal(135, 2), filtered.combined)
    }

    @Test fun negativeBalancesAreRejected() = runTest {
        val balances = repo.observeOpeningBalances().first().map { it.copy(minutes = -1) }
        assertEquals(SaveResult.Rejected(msg(MsgKey.BALANCES_NEGATIVE)), repo.saveOpeningBalances(balances))
    }
}
