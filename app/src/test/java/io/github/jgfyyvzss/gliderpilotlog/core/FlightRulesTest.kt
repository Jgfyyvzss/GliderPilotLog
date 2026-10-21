package io.github.jgfyyvzss.gliderpilotlog.core

import io.github.jgfyyvzss.gliderpilotlog.english
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlightRulesTest {
    private val complete = FlightDraft(
        logNoText = "340",
        date = LocalDate.of(2026, 10, 17),
        durationText = "4:31",
        gliderId = "g",
        airfieldId = "a",
        clubId = null,
        launchTypeId = "lt",
        role = Role.PIC,
        picName = "Pat Pilot",
    )
    private val noClubs = FlightContext(airfieldClubCount = 0, logNoHeldBy = null)

    private fun errors(draft: FlightDraft, context: FlightContext = noClubs) =
        (FlightRules.validate(draft, context) as FlightValidation.Invalid).errors.mapValues { it.value.english() }

    private fun valid(draft: FlightDraft, context: FlightContext = noClubs) =
        FlightRules.validate(draft, context) as FlightValidation.Valid

    @Test fun completeDraftIsValid() {
        val f = valid(complete).flight
        assertEquals(340, f.logNo)
        assertEquals(271, f.durationMinutes)
        assertNull(f.clubId)
        assertNull(f.notes)
    }

    @Test fun overflowDurationIsStoredAsMinutes() {
        assertEquals(135, valid(complete.copy(durationText = "1:75")).flight.durationMinutes)
    }

    @Test fun digitEntryForDurationAndTimes() {
        val f = valid(complete.copy(durationText = "431", launchTimeText = "1305", landingTimeText = "1736")).flight
        assertEquals(271, f.durationMinutes)
        assertEquals("13:05", f.launchTime.toString())
        assertEquals("17:36", f.landingTime.toString())
        assertEquals(271, FlightRules.durationFromTimes("1305", "1736"))
    }

    @Test fun logNoMustBePositiveAndFree() {
        listOf("", "0", "-3", "1.5", "abc").forEach {
            assertTrue(it, FlightField.LOG_NO in errors(complete.copy(logNoText = it)))
        }
        val held = errors(complete, noClubs.copy(logNoHeldBy = msg(MsgKey.FLIGHT_ON, LocalDate.of(2026, 10, 17), "FQM")))
        assertEquals("Log number 340 is already used by the flight on 2026-10-17 in FQM", held[FlightField.LOG_NO])
    }

    @Test fun requiredFields() {
        val e = errors(FlightDraft())
        listOf(
            FlightField.LOG_NO, FlightField.DATE, FlightField.DURATION, FlightField.GLIDER,
            FlightField.AIRFIELD, FlightField.LAUNCH_TYPE, FlightField.PIC_NAME,
        ).forEach { assertTrue(it.name, it in e) }
    }

    @Test fun durationRange() {
        assertTrue(FlightField.DURATION in errors(complete.copy(durationText = "0:00")))
        assertTrue(FlightField.DURATION in errors(complete.copy(durationText = "24:01")))
        assertEquals(1440, valid(complete.copy(durationText = "24:00")).flight.durationMinutes)
    }

    @Test fun clubRequiredWhenAirfieldHasClubs() {
        assertTrue(FlightField.CLUB in errors(complete, noClubs.copy(airfieldClubCount = 2)))
        assertEquals("c", valid(complete.copy(clubId = "c"), noClubs.copy(airfieldClubCount = 1)).flight.clubId)
        // A club left over from a previously chosen airfield is not stored.
        assertNull(valid(complete.copy(clubId = "c")).flight.clubId)
    }

    @Test fun picNameRequiredAndTrimmed() {
        assertTrue(FlightField.PIC_NAME in errors(complete.copy(picName = "  ")))
        assertEquals(
            "Enter the name of the pilot in command",
            errors(complete.copy(role = Role.STUDENT, picName = ""))[FlightField.PIC_NAME],
        )
        assertEquals("Pat Pilot", valid(complete.copy(picName = " Pat Pilot ")).flight.picName)
    }

    @Test fun timesAreOptionalButChecked() {
        assertNull(valid(complete).flight.launchTime)
        assertTrue(FlightField.LAUNCH_TIME in errors(complete.copy(launchTimeText = "25:00")))
        assertEquals(
            "Landing must be after launch",
            errors(complete.copy(launchTimeText = "13:05", landingTimeText = "13:05"))[FlightField.LANDING_TIME],
        )
        val f = valid(complete.copy(launchTimeText = "13:05", landingTimeText = "17:36")).flight
        assertEquals("13:05", f.launchTime.toString())
        assertEquals("17:36", f.landingTime.toString())
    }

    @Test fun warnsWhenTimesAndDurationDisagreeByMoreThanAMinute() {
        assertTrue(valid(complete.copy(launchTimeText = "13:05", landingTimeText = "17:37")).warnings.isEmpty())
        assertEquals(1, valid(complete.copy(launchTimeText = "13:05", landingTimeText = "17:38")).warnings.size)
    }

    @Test fun durationFromTimes() {
        assertEquals(271, FlightRules.durationFromTimes("13:05", "17:36"))
        assertNull(FlightRules.durationFromTimes("13:05", ""))
        assertNull(FlightRules.durationFromTimes("17:36", "13:05"))
    }

    @Test fun landingPlaceChoices() {
        val same = valid(complete.copy(landingAirfieldId = "x", landingPlace = "paddock")).flight
        assertNull(same.landingAirfieldId)
        assertNull(same.landingPlace)

        val airfield = valid(complete.copy(landingChoice = LandingChoice.AIRFIELD, landingAirfieldId = "b", landingPlace = "paddock")).flight
        assertEquals("b", airfield.landingAirfieldId)
        assertNull(airfield.landingPlace)

        val other = valid(complete.copy(landingChoice = LandingChoice.OTHER, landingAirfieldId = "b", landingPlace = " Paddock ")).flight
        assertNull(other.landingAirfieldId)
        assertEquals("Paddock", other.landingPlace)

        assertTrue(FlightField.LANDING_AIRFIELD in errors(complete.copy(landingChoice = LandingChoice.AIRFIELD)))
        assertTrue(FlightField.LANDING_PLACE in errors(complete.copy(landingChoice = LandingChoice.OTHER)))
    }
}
