package io.github.jgfyyvzss.soarlog.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeysAndTimesTest {
    @Test fun registrationKeyIgnoresCaseSpacesAndHyphens() {
        assertEquals("vhfqm", Keys.registrationKey("VH-FQM"))
        assertEquals("vhfqm", Keys.registrationKey("vh fqm"))
        assertEquals(Keys.registrationKey("FQM"), Keys.registrationKey("f-q m"))
    }

    @Test fun registrationKeyKeepsOtherCharacters() {
        assertEquals("?", Keys.registrationKey("?"))
        assertEquals("b4", Keys.registrationKey("B4"))
        assertEquals("d.kabc", Keys.registrationKey("D.KABC"))
    }

    @Test fun nameKeyTrimsAndLowerCases() {
        assertEquals("mt beauty", Keys.nameKey("  Mt Beauty "))
        assertNotEquals(Keys.nameKey("Mt Beauty"), Keys.nameKey("Mount Beauty"))
    }

    @Test fun seededLaunchTypeIdsAreStable() {
        assertEquals(Keys.seededLaunchTypeId("Aero"), Keys.seededLaunchTypeId("aero"))
        assertEquals(4, Keys.seededLaunchTypes.map(Keys::seededLaunchTypeId).toSet().size)
    }

    @Test fun clockTimeParsesAndFormats() {
        assertEquals("09:30", ClockTime.parse("9:30").toString())
        assertEquals("23:59", ClockTime.parse("23:59").toString())
        listOf("24:00", "12:60", "1230", "12:5", "", "ab:cd").forEach { assertNull(it, ClockTime.parse(it)) }
    }

    @Test fun clockTimeFormEntryAcceptsDigits() {
        assertEquals("13:05", ClockTime.parseFormEntry("1305").toString())
        assertEquals("09:05", ClockTime.parseFormEntry("905").toString())
        assertEquals("00:00", ClockTime.parseFormEntry("000").toString())
        assertEquals("09:30", ClockTime.parseFormEntry("9:30").toString())
        listOf("13", "5", "2400", "1260", "13050", "", "ab:cd").forEach { assertNull(it, ClockTime.parseFormEntry(it)) }
    }

    @Test fun minutesBetweenRequiresLandingAfterLaunch() {
        val launch = ClockTime.parse("13:05")!!
        assertEquals(271, ClockTime.minutesBetween(launch, ClockTime.parse("17:36")!!))
        assertNull(ClockTime.minutesBetween(launch, launch))
        assertNull(ClockTime.minutesBetween(launch, ClockTime.parse("12:00")!!))
    }
}
