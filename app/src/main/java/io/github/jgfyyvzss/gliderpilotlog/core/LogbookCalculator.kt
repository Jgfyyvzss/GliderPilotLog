package io.github.jgfyyvzss.gliderpilotlog.core

import java.time.LocalDate
import kotlin.math.roundToInt

/** The figures typed into the aircraft logbook calculator, in minutes and flights. */
data class LogbookCalc(
    val dayMinutes: Int,
    val dayFlights: Int,
    val extraMinutes: Int = 0,
    val extraFlights: Int = 0,
    val beforeMinutes: Int,
    /** The flight count is optional in the glider record; null keeps the new count blank. */
    val beforeFlights: Int?,
) {
    val newMinutes: Int get() = beforeMinutes + dayMinutes + extraMinutes
    val newFlights: Int? get() = beforeFlights?.let { it + dayFlights + extraFlights }
}

/**
 * The aircraft logbook calculator. It adds a day's flying to the total last written in the glider's own logbook,
 * and never touches the pilot's totals. Role does not matter, since airframe time counts for every role.
 */
object LogbookCalculator {
    private val digits = Regex("""^\s*(\d{1,8})\s*$""")
    private val hm = Regex("""^\s*(\d{1,6}):(\d{1,3})\s*$""")
    private val decimal = Regex("""^\s*(\d{0,6})[.,](\d{1,3})\s*$""")

    /**
     * Reads an hours figure, which for an airframe can pass 10,000 hours. Accepts digits with the last two as
     * minutes (123456 is 1234:56), H:MM (1234:56, minutes may overflow), or decimal hours (1234.9), rounded to the
     * nearest minute. Blank is zero. Returns null for anything else.
     */
    fun parseHours(text: String): Int? {
        if (text.isBlank()) return 0
        hm.matchEntire(text)?.let { return it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }
        decimal.matchEntire(text)?.let {
            val hours = "${it.groupValues[1].ifEmpty { "0" }}.${it.groupValues[2]}".toDouble()
            return (hours * 60).roundToInt()
        }
        digits.matchEntire(text)?.let {
            val d = it.groupValues[1]
            return d.dropLast(2).ifEmpty { "0" }.toInt() * 60 + d.takeLast(2).toInt()
        }
        return null
    }

    /** A flight count; blank is zero. */
    fun parseCount(text: String): Int? = if (text.isBlank()) 0 else text.trim().toIntOrNull()?.takeIf { it >= 0 }

    /**
     * True when the glider's stored total is dated on or after the day being added, so the day may already be in
     * it. Adding the same day twice is the easiest way to corrupt the figure.
     */
    fun mayAlreadyInclude(storedDate: LocalDate?, day: LocalDate): Boolean = storedDate != null && !storedDate.isBefore(day)

    fun sum(durations: List<Int>): Int = durations.sum()
}
