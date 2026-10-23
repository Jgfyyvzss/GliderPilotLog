package io.github.jgfyyvzss.soarlog.core.pdf

import io.github.jgfyyvzss.soarlog.core.Role
import io.github.jgfyyvzss.soarlog.core.RoleTotal
import java.time.LocalDate

/** One flight as the PDF prints it, oldest first like a paper logbook. */
data class PdfFlight(
    val logNo: Int,
    val date: LocalDate,
    val registration: String,
    val gliderType: String,
    val airfield: String,
    val club: String?,
    val launchTypeId: String,
    val launchTypeName: String,
    val role: Role,
    val pic: String,
    val durationMinutes: Int,
    val launchTime: String?,
    val landingTime: String?,
    /** A landing airfield's name or a landing place; null when the flight ended where it began. */
    val landingPlace: String?,
    val notes: String?,
    /** The witness, when the flight is witnessed, its seal still matches and the Witness sign-off Setting is on. */
    val witnessName: String? = null,
    val witnessIdentifier: String? = null,
)

/** The Settings and figures that shape an export. */
data class PdfOptions(
    val instructionCountsAsPic: Boolean,
    val recordTimes: Boolean,
    val recordLandingPlace: Boolean,
    /** The opening balances when no filter is active, so page 1 brings them forward; null for a filtered export. */
    val openingBalances: Map<Role, RoleTotal>?,
)

/** Minutes per printed column (zero where a column has no total) and the number of flights. */
data class PageTotals(val minutes: List<Int>, val flights: Int) {
    operator fun plus(o: PageTotals) = PageTotals(minutes.zip(o.minutes) { a, b -> a + b }, flights + o.flights)
}

/** One printed page: its flights and its three totals rows. */
data class LogbookPage(
    val number: Int,
    val flights: List<PdfFlight>,
    val broughtForward: PageTotals,
    val pageTotal: PageTotals,
    val carriedForward: PageTotals,
)

/**
 * Splits flights into pages and keeps the running totals: brought forward, the page's own total, and carried
 * forward (the grand total to date). Columns for the optional fields appear only when their Settings are on. The
 * arithmetic matches the on-screen totals: every flight counts once in each column whose roles take it.
 */
class LogbookLayout(val template: LogbookTemplate, private val options: PdfOptions) {
    /** The printed columns, after dropping those whose Setting is off. */
    val columns: List<ColumnSpec> = template.columns.filter { c ->
        when (c.field) {
            Field.LAUNCH_TIME, Field.LANDING_TIME -> options.recordTimes
            Field.LANDING_PLACE -> options.recordLandingPlace
            else -> true
        }
    }

    fun pageCount(flights: Int): Int = maxOf(1, (flights + template.rowsPerPage - 1) / template.rowsPerPage)

    /** The minutes a flight puts in a column, or null when the column does not take its role or is not a time. */
    fun minutes(column: ColumnSpec, flight: PdfFlight): Int? =
        if (column.isTime && column.takes(flight.role, options.instructionCountsAsPic)) flight.durationMinutes else null

    /** What page 1 brings forward: the opening balances when unfiltered, otherwise nothing. */
    val opening: PageTotals = run {
        val balances = options.openingBalances
        PageTotals(
            columns.map { c ->
                if (balances == null || !c.totals) 0
                else balances.filterKeys { c.takes(it, options.instructionCountsAsPic) }.values.sumOf { it.minutes }
            },
            balances?.values?.sumOf { it.flights } ?: 0,
        )
    }

    private fun totalsOf(flights: List<PdfFlight>) = PageTotals(
        columns.map { c -> if (!c.totals) 0 else flights.sumOf { f -> minutes(c, f) ?: 0 } },
        flights.size,
    )

    /** One page of up to [LogbookTemplate.rowsPerPage] flights, continuing from [forward]. */
    fun page(number: Int, flights: List<PdfFlight>, forward: PageTotals): LogbookPage {
        val total = totalsOf(flights)
        return LogbookPage(number, flights, forward, total, forward + total)
    }

    /** The pages, built lazily so a long logbook never sits in memory at once. */
    fun pages(flights: Sequence<PdfFlight>): Sequence<LogbookPage> = sequence {
        var forward = opening
        var number = 0
        for (chunk in flights.chunked(template.rowsPerPage)) {
            val p = page(++number, chunk, forward)
            yield(p)
            forward = p.carriedForward
        }
    }
}
