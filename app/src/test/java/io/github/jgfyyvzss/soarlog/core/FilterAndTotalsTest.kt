package io.github.jgfyyvzss.soarlog.core

import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterAndTotalsTest {
    @Test fun emptyFilterSelectsLiveFlightsOnly() {
        assertFalse(FlightFilter().isActive)
        assertEquals(SqlCondition("f.deleted_at IS NULL", emptyList()), FilterSql.where(FlightFilter()))
    }

    @Test fun everyCriterionBecomesAnInclusiveCondition() {
        val f = FlightFilter(
            gliderIds = setOf("g"), airfieldIds = setOf("a"), clubIds = setOf("c"),
            dateFrom = LocalDate.of(2026, 1, 1), dateTo = LocalDate.of(2026, 12, 31),
            roles = setOf(Role.INSTRUCTOR), logNoFrom = 100, logNoTo = 200,
            launchFrom = ClockTime.parse("10:00"), launchTo = ClockTime.parse("12:30"),
        )
        assertTrue(f.isActive)
        val w = FilterSql.where(f)
        assertEquals(
            "f.deleted_at IS NULL AND f.glider_id IN (?) AND f.airfield_id IN (?) AND f.club_id IN (?) AND f.date >= ? AND " +
                "f.date <= ? AND f.role IN (?) AND f.log_no >= ? AND f.log_no <= ? AND f.launch_time >= ? AND f.launch_time <= ?",
            w.sql,
        )
        assertEquals(listOf("g", "a", "c", "2026-01-01", "2026-12-31", "INSTRUCTOR", 100, 200, "10:00", "12:30"), w.args)
    }

    @Test fun severalChoicesMatchAnyOfThem() {
        val w = FilterSql.where(FlightFilter(gliderIds = setOf("g2", "g1"), roles = setOf(Role.STUDENT, Role.PIC)))
        assertEquals("f.deleted_at IS NULL AND f.glider_id IN (?,?) AND f.role IN (?,?)", w.sql)
        assertEquals(listOf("g1", "g2", "PIC", "STUDENT"), w.args)
        assertFalse(FlightFilter(gliderIds = emptySet()).isActive)
    }

    @Test fun singleCriterionHasOneArgument() {
        val w = FilterSql.where(FlightFilter(logNoTo = 50))
        assertEquals("f.deleted_at IS NULL AND f.log_no <= ?", w.sql)
        assertEquals(listOf<Any>(50), w.args)
    }

    @Test fun witnessChoicesBecomeConditions() {
        val live = "f.deleted_at IS NULL"
        assertEquals("$live AND f.witness_signoff_id IS NULL", FilterSql.where(FlightFilter(witness = WitnessChoice.NOT_WITNESSED)).sql)

        val changed = setOf("f2", "f1")
        val witnessed = FilterSql.where(FlightFilter(witness = WitnessChoice.WITNESSED, changedIds = changed))
        assertEquals("$live AND f.witness_signoff_id IS NOT NULL AND f.id NOT IN (?,?)", witnessed.sql)
        assertEquals(listOf("f1", "f2"), witnessed.args)
        assertEquals(
            "with nothing changed, every witnessed flight matches",
            "$live AND f.witness_signoff_id IS NOT NULL", FilterSql.where(FlightFilter(witness = WitnessChoice.WITNESSED)).sql,
        )

        val onlyChanged = FilterSql.where(FlightFilter(witness = WitnessChoice.CHANGED, changedIds = changed))
        assertEquals("$live AND f.id IN (?,?)", onlyChanged.sql)
        assertEquals(listOf("f1", "f2"), onlyChanged.args)
        assertEquals("nothing changed matches nothing", "$live AND 0", FilterSql.where(FlightFilter(witness = WitnessChoice.CHANGED)).sql)
    }

    @Test fun theChangedFlightsDoNotMakeAFilterActive() {
        assertFalse(FlightFilter(changedIds = setOf("f1")).isActive)
        assertTrue(FlightFilter(witness = WitnessChoice.CHANGED).isActive)
        assertTrue(FlightFilter(witness = WitnessChoice.CHANGED).needsChangedIds)
        assertTrue(FlightFilter(witness = WitnessChoice.WITNESSED).needsChangedIds)
        assertFalse(FlightFilter(witness = WitnessChoice.NOT_WITNESSED).needsChangedIds)
    }

    private val flights = mapOf(
        Role.PIC to RoleTotal(600, 10),
        Role.STUDENT to RoleTotal(120, 4),
        Role.INSTRUCTOR to RoleTotal(90, 6),
        Role.COACH to RoleTotal(30, 1),
    )
    private val openings = mapOf(
        Role.PIC to RoleTotal(6000, 300),
        Role.STUDENT to RoleTotal(1200, 80),
        Role.MUTUAL to RoleTotal(60, 2),
    )

    @Test fun noFilterAddsOpeningBalances() {
        val t = Totals.table(flights, openings, filterActive = false, instructionCountsAsPic = false)
        assertEquals(RoleTotal(6600, 310), t.rows[Role.PIC])
        assertEquals(RoleTotal(1320, 84), t.rows[Role.STUDENT])
        assertEquals(RoleTotal(90, 6), t.rows[Role.INSTRUCTOR])
        assertEquals(RoleTotal(60, 2), t.rows[Role.MUTUAL])
        assertEquals(RoleTotal(600 + 120 + 90 + 30 + 6000 + 1200 + 60, 10 + 4 + 6 + 1 + 300 + 80 + 2), t.combined)
        assertTrue(t.includesOpeningBalances)
    }

    @Test fun filterLeavesOpeningBalancesOut() {
        val t = Totals.table(flights, openings, filterActive = true, instructionCountsAsPic = false)
        assertEquals(RoleTotal(600, 10), t.rows[Role.PIC])
        assertEquals(RoleTotal(0, 0), t.rows[Role.MUTUAL])
        assertEquals(RoleTotal(840, 21), t.combined)
        assertFalse(t.includesOpeningBalances)
    }

    @Test fun instructionCountsAsPicChangesOnlyThePicRow() {
        val t = Totals.table(flights, openings, filterActive = false, instructionCountsAsPic = true)
        assertEquals(RoleTotal(6600 + 90 + 30, 310 + 6 + 1), t.rows[Role.PIC])
        assertEquals(RoleTotal(90, 6), t.rows[Role.INSTRUCTOR])
        // Combined still counts each flight once.
        assertEquals(Totals.table(flights, openings, false, false).combined, t.combined)
        assertTrue(t.picIncludesInstruction)
    }

    @Test fun emptyLogbookHasZeroRows() {
        val t = Totals.table(emptyMap(), emptyMap(), filterActive = false, instructionCountsAsPic = false)
        Role.entries.forEach { assertEquals(RoleTotal(), t.rows[it]) }
        assertEquals(RoleTotal(), t.combined)
    }

    @Test fun hoursFormat() {
        assertEquals("4:31", HoursFormat.format(271, HoursDisplay.HM))
        assertEquals("4.5", HoursFormat.format(271, HoursDisplay.DECIMAL, Locale.ROOT))
        assertEquals("0.1", HoursFormat.format(3, HoursDisplay.DECIMAL, Locale.ROOT))
        assertEquals("0.0", HoursFormat.format(2, HoursDisplay.DECIMAL, Locale.ROOT))
        assertEquals("1234.9", HoursFormat.format(1234 * 60 + 56, HoursDisplay.DECIMAL, Locale.ROOT))
        assertEquals("4,5", HoursFormat.format(271, HoursDisplay.DECIMAL, Locale.GERMANY))
    }
}
