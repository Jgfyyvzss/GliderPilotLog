package io.github.jgfyyvzss.soarlog.core

/** Duration arithmetic. Durations are stored as whole minutes. */
object Durations {
    const val MIN_FLIGHT_MINUTES = 1
    const val MAX_FLIGHT_MINUTES = 1440

    private val hm = Regex("""^\s*(\d{1,4}):(\d{1,3})\s*$""")

    /**
     * Parses H:MM as entered on the flight form. Minutes may exceed 59, so 1:75 is 135 minutes.
     * Returns null for anything else.
     */
    fun parseHm(text: String): Int? {
        val m = hm.matchEntire(text) ?: return null
        val hours = m.groupValues[1].toInt()
        val minutes = m.groupValues[2].toInt()
        return hours * 60 + minutes
    }

    private val digits = Regex("""^\s*(\d{1,6})\s*$""")

    /**
     * Parses the flight form's duration. Digits alone read as hours then two digits of minutes, so 51 is 0:51,
     * 324 is 3:24 and 175 is 1:75, which is 2:15. H:MM with a colon is also accepted.
     */
    fun parseFormEntry(text: String): Int? {
        parseHm(text)?.let { return it }
        val d = digits.matchEntire(text)?.groupValues?.get(1) ?: return null
        val minutes = d.takeLast(2).toInt()
        val hours = d.dropLast(2).ifEmpty { "0" }.toInt()
        return hours * 60 + minutes
    }

    /** Formats minutes as H:MM, for example 135 as 2:15. */
    fun formatHm(minutes: Int): String {
        require(minutes >= 0) { "negative duration" }
        return "%d:%02d".format(minutes / 60, minutes % 60)
    }

    fun isValidFlightDuration(minutes: Int): Boolean =
        minutes in MIN_FLIGHT_MINUTES..MAX_FLIGHT_MINUTES
}
