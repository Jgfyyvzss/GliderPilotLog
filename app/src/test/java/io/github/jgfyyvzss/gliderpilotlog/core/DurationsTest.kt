package io.github.jgfyyvzss.gliderpilotlog.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DurationsTest {
    @Test fun parsesHm() {
        assertEquals(135, Durations.parseHm("2:15"))
        assertEquals(1, Durations.parseHm("0:01"))
        assertEquals(271, Durations.parseHm(" 4:31 "))
        assertEquals(600, Durations.parseHm("10:00"))
    }

    @Test fun acceptsMinuteOverflow() {
        assertEquals(135, Durations.parseHm("1:75"))
        assertEquals(Durations.parseHm("2:15"), Durations.parseHm("1:75"))
        assertEquals(150, Durations.parseHm("0:150"))
    }

    @Test fun rejectsOtherForms() {
        listOf("", "215", "2.15", "2:", ":15", "-1:00", "a:bc", "2:15:00").forEach {
            assertNull(it, Durations.parseHm(it))
        }
    }

    @Test fun formatsHm() {
        assertEquals("2:15", Durations.formatHm(135))
        assertEquals("0:01", Durations.formatHm(1))
        assertEquals("24:00", Durations.formatHm(1440))
        assertEquals("1234:56", Durations.formatHm(1234 * 60 + 56))
    }

    @Test fun flightDurationRange() {
        assertFalse(Durations.isValidFlightDuration(0))
        assertTrue(Durations.isValidFlightDuration(1))
        assertTrue(Durations.isValidFlightDuration(1440))
        assertFalse(Durations.isValidFlightDuration(1441))
    }
}
