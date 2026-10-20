package io.github.jgfyyvzss.gliderpilotlog.core

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogbookCalculatorTest {
    @Test fun parsesDigitsHmAndDecimalHours() {
        assertEquals(1234 * 60 + 56, LogbookCalculator.parseHours("123456"))
        assertEquals(12345 * 60 + 30, LogbookCalculator.parseHours("1234530"))
        assertEquals(1234 * 60 + 56, LogbookCalculator.parseHours("1234:56"))
        assertEquals(12345 * 60 + 6, LogbookCalculator.parseHours("12345:06"))
        assertEquals(135, LogbookCalculator.parseHours("1:75"))
        assertEquals(1234 * 60 + 54, LogbookCalculator.parseHours("1234.9"))
        assertEquals(30, LogbookCalculator.parseHours("0.5"))
        assertEquals(45, LogbookCalculator.parseHours(".75"))
        assertEquals(90, LogbookCalculator.parseHours("1,5"))
        assertEquals(0, LogbookCalculator.parseHours(""))
        listOf("abc", "1:2:3", "-5", "1.5.5").forEach { assertNull(it, LogbookCalculator.parseHours(it)) }
    }

    @Test fun addsDayAndExtraToTheTotalWithOverflow() {
        val c = LogbookCalc(dayMinutes = 105, dayFlights = 3, extraMinutes = 30, extraFlights = 1, beforeMinutes = 1234 * 60 + 56, beforeFlights = 5678)
        // 1234:56 + 1:45 + 0:30 = 1237:11
        assertEquals(1237 * 60 + 11, c.newMinutes)
        assertEquals("1237:11", Durations.formatHm(c.newMinutes))
        assertEquals(5682, c.newFlights)
    }

    @Test fun blankStoredFlightCountStaysBlank() {
        assertNull(LogbookCalc(60, 2, beforeMinutes = 600, beforeFlights = null).newFlights)
    }

    @Test fun daySumsTheTickedFlights() {
        val durations = listOf(271, 5, 45)
        assertEquals(321, LogbookCalculator.sum(durations))
        // Unticking the two short flights leaves only the first.
        assertEquals(271, LogbookCalculator.sum(durations.filter { it > 100 }))
    }

    @Test fun doubleAddWarning() {
        val day = LocalDate.of(2026, 10, 18)
        assertFalse(LogbookCalculator.mayAlreadyInclude(null, day))
        assertFalse(LogbookCalculator.mayAlreadyInclude(day.minusDays(1), day))
        assertTrue(LogbookCalculator.mayAlreadyInclude(day, day))
        assertTrue(LogbookCalculator.mayAlreadyInclude(day.plusDays(3), day))
    }
}
