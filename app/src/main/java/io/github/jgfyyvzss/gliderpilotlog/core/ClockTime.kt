package io.github.jgfyyvzss.gliderpilotlog.core

/** A local 24-hour clock time, stored as HH:MM text. */
@JvmInline
value class ClockTime(val minuteOfDay: Int) {
    init {
        require(minuteOfDay in 0 until 24 * 60)
    }

    override fun toString(): String = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

    companion object {
        private val pattern = Regex("""^\s*(\d{1,2}):(\d{2})\s*$""")

        /** Accepts H:MM or HH:MM with hours 0 to 23 and minutes 0 to 59. */
        fun parse(text: String): ClockTime? {
            val m = pattern.matchEntire(text) ?: return null
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            if (h > 23 || min > 59) return null
            return ClockTime(h * 60 + min)
        }

        private val digits = Regex("""^\s*(\d{3,4})\s*$""")

        /** Parses the flight form's entry: three or four digits such as 905 or 1305, or H:MM. */
        fun parseFormEntry(text: String): ClockTime? {
            parse(text)?.let { return it }
            val d = digits.matchEntire(text)?.groupValues?.get(1) ?: return null
            return parse("${d.dropLast(2)}:${d.takeLast(2)}")
        }

        /** Minutes from launch to landing, or null unless landing is after launch. */
        fun minutesBetween(launch: ClockTime, landing: ClockTime): Int? {
            val diff = landing.minuteOfDay - launch.minuteOfDay
            return if (diff > 0) diff else null
        }
    }
}
