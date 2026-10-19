package io.github.jgfyyvzss.gliderpilotlog.core

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

    /** Formats minutes as H:MM, for example 135 as 2:15. */
    fun formatHm(minutes: Int): String {
        require(minutes >= 0) { "negative duration" }
        return "%d:%02d".format(minutes / 60, minutes % 60)
    }

    fun isValidFlightDuration(minutes: Int): Boolean =
        minutes in MIN_FLIGHT_MINUTES..MAX_FLIGHT_MINUTES
}
