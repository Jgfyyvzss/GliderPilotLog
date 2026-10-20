package io.github.jgfyyvzss.gliderpilotlog.core

import java.util.Locale
import kotlin.math.roundToInt

/** Minutes and flights for one role bucket. */
data class RoleTotal(val minutes: Int = 0, val flights: Int = 0) {
    operator fun plus(other: RoleTotal) = RoleTotal(minutes + other.minutes, flights + other.flights)
}

/** The Totals grid: one row per role, plus Combined. */
data class TotalsTable(
    val rows: Map<Role, RoleTotal>,
    val combined: RoleTotal,
    /** True when opening balances are included, which is only when no filter is active. */
    val includesOpeningBalances: Boolean,
    /** True when the PIC row also counts Instructor and Coach flights. */
    val picIncludesInstruction: Boolean,
)

object Totals {
    /**
     * Builds the grid from the pilot's flights per role. With no filter active, each role adds its opening
     * balance; with a filter, only matching flights count. When [instructionCountsAsPic] is on, the PIC row
     * also includes Instructor and Coach, opening balances included. Combined counts each flight once.
     */
    fun table(
        flights: Map<Role, RoleTotal>,
        openingBalances: Map<Role, RoleTotal>,
        filterActive: Boolean,
        instructionCountsAsPic: Boolean,
    ): TotalsTable {
        val buckets = Role.entries.associateWith { role ->
            val own = flights[role] ?: RoleTotal()
            if (filterActive) own else own + (openingBalances[role] ?: RoleTotal())
        }
        val combined = buckets.values.fold(RoleTotal()) { a, b -> a + b }
        val rows = if (instructionCountsAsPic) {
            buckets + (Role.PIC to buckets.getValue(Role.PIC) + buckets.getValue(Role.INSTRUCTOR) + buckets.getValue(Role.COACH))
        } else {
            buckets
        }
        return TotalsTable(rows, combined, includesOpeningBalances = !filterActive, picIncludesInstruction = instructionCountsAsPic)
    }
}

/** How hours are shown: H:MM, or decimal hours to one place. */
enum class HoursDisplay { HM, DECIMAL }

object HoursFormat {
    fun format(minutes: Int, display: HoursDisplay, locale: Locale = Locale.getDefault()): String = when (display) {
        HoursDisplay.HM -> Durations.formatHm(minutes)
        HoursDisplay.DECIMAL -> String.format(locale, "%.1f", (minutes / 6.0).roundToInt() / 10.0)
    }
}
