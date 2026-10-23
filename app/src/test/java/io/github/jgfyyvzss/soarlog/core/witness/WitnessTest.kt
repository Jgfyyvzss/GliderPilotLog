package io.github.jgfyyvzss.soarlog.core.witness

import io.github.jgfyyvzss.soarlog.core.MsgKey
import io.github.jgfyyvzss.soarlog.core.Role
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seal's test vectors were computed separately, with a short Python script following the written rules in
 * docs/Witness_Sign-Off.md, so these tests check the format itself and not just this code against itself. The
 * names are made up.
 */
class WitnessTest {
    private val student = SealInput(
        logNo = 340, date = LocalDate.of(2026, 10, 17), durationMinutes = 271,
        gliderId = "g-1", airfieldId = "a-1", launchTypeId = "lt-winch", role = "STUDENT", picName = "Chris Instructor",
        launchTime = "13:05", landingTime = "17:36", landingAirfieldId = null, landingPlace = null,
        witnessName = "Chris Instructor", witnessIdentifier = "12345", signedAt = 1_792_222_800_000, photoSha256 = null,
    )

    private val escaped = SealInput(
        logNo = 7, date = LocalDate.of(2026, 1, 2), durationMinutes = 5,
        gliderId = "g-2", airfieldId = "a-2", launchTypeId = "lt-aero", role = "PIC", picName = "Sam",
        launchTime = null, landingTime = null, landingAirfieldId = null, landingPlace = "Paddock\nnear C:\\x",
        witnessName = "Lee", witnessIdentifier = null, signedAt = 1_767_398_400_250,
        photoSha256 = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
    )

    @Test fun canonicalTextFollowsTheFixedOrder() {
        assertEquals(
            "seal_version=1\nlog_no=340\ndate=2026-10-17\nduration_minutes=271\nglider_id=g-1\nairfield_id=a-1\n" +
                "launch_type_id=lt-winch\nrole=STUDENT\npic_name=Chris Instructor\nlaunch_time=13:05\nlanding_time=17:36\n" +
                "landing_airfield_id=\nlanding_place=\nwitness_name=Chris Instructor\nwitness_identifier=12345\n" +
                "signed_at=2026-10-17T07:40:00Z\nphoto_sha256=\n",
            WitnessSeal.canonical(student),
        )
    }

    @Test fun sealsMatchTheTestVectors() {
        assertEquals("bad8f84e024e10cb0d7049e816d82d10016600a3216f7c1a55f76d30585c361c", WitnessSeal.seal(student))
        // A line break and a backslash in a value are escaped, and milliseconds show in the signing time.
        assertTrue(WitnessSeal.canonical(escaped).contains("landing_place=Paddock\\nnear C:\\\\x\n"))
        assertTrue(WitnessSeal.canonical(escaped).contains("signed_at=2026-01-03T00:00:00.250Z\n"))
        assertEquals("8d0cfb258062da560173e372fb5c238d452836981233a2fe6c5b2482884bf367", WitnessSeal.seal(escaped))
    }

    @Test fun anEditToASignedFieldShowsButNotesAndClubAreNotSigned() {
        val seal = WitnessSeal.seal(student)
        assertEquals(SealStatus.WITNESSED, WitnessSeal.check(student, seal, WitnessSeal.VERSION))
        listOf(
            student.copy(durationMinutes = 272),
            student.copy(gliderId = "g-9"),
            student.copy(picName = "Someone Else"),
            student.copy(landingPlace = "Paddock"),
            student.copy(date = LocalDate.of(2026, 10, 18)),
        ).forEach { assertEquals(it.toString(), SealStatus.CHANGED, WitnessSeal.check(it, seal, WitnessSeal.VERSION)) }
        // Notes and club are not in SealInput at all, so editing them cannot change the seal.
        assertEquals(SealStatus.WITNESSED, WitnessSeal.check(student, seal.uppercase(), WitnessSeal.VERSION))
    }

    @Test fun anUnknownSealVersionCannotBeVerified() {
        assertEquals(SealStatus.CANNOT_VERIFY, WitnessSeal.check(student, WitnessSeal.seal(student), 2))
    }

    // Checks

    private val pic = WitnessedFlight(Role.PIC, "Pat Pilot")

    @Test fun theNameIsRequiredAndTheNumberIsNot() {
        assertEquals(listOf(MsgKey.WITNESS_NAME_REQUIRED), WitnessChecks.check(" ", null, true, "Pat Pilot", emptyList(), listOf(pic)).errors.map { it.key })
        assertTrue(WitnessChecks.check("Lee", null, true, "Pat Pilot", emptyList(), listOf(pic)).ok)
        assertEquals(listOf(MsgKey.WITNESS_NO_FLIGHTS), WitnessChecks.check("Lee", null, true, "Pat Pilot", emptyList(), emptyList()).errors.map { it.key })
        assertEquals(
            "the photo is the evidence, so it is required",
            listOf(MsgKey.WITNESS_PHOTO_REQUIRED), WitnessChecks.check("Lee", null, false, "Pat Pilot", emptyList(), listOf(pic)).errors.map { it.key },
        )
    }

    @Test fun theWitnessCannotBeThePilot() {
        fun errors(name: String, number: String?) =
            WitnessChecks.check(name, number, true, "Pat Pilot", listOf("GFA 123-45"), listOf(pic)).errors.map { it.key }
        assertEquals(listOf(MsgKey.WITNESS_IS_PILOT), errors("  pat   PILOT ", null))
        assertEquals(listOf(MsgKey.WITNESS_IS_PILOT), errors("Lee", "gfa12345"))
        assertEquals(emptyList<MsgKey>(), errors("Lee", "999"))
        // A blank profile name never matches.
        assertTrue(WitnessChecks.check("Lee", null, true, "", emptyList(), listOf(pic)).ok)
    }

    @Test fun studentFlightsPrefillTheWitnessFromThePic() {
        val a = WitnessedFlight(Role.STUDENT, "Chris Instructor")
        val b = WitnessedFlight(Role.STUDENT, "chris  instructor")
        assertEquals("Chris Instructor", WitnessChecks.studentPic(listOf(a, b, pic)))
        assertNull(WitnessChecks.studentPic(listOf(a, WitnessedFlight(Role.STUDENT, "Dana"))))
        assertNull(WitnessChecks.studentPic(listOf(pic)))
        val other = WitnessChecks.check("Lee", null, true, "Pat Pilot", emptyList(), listOf(a))
        assertTrue(other.ok)
        assertEquals(listOf(MsgKey.WITNESS_NOT_PIC), other.warnings.map { it.key })
        assertTrue(WitnessChecks.check("Chris Instructor", null, true, "Pat Pilot", emptyList(), listOf(a)).warnings.isEmpty())
    }

    @Test fun daysAfterTheFlight() {
        val zone = ZoneId.of("Australia/Melbourne")
        assertEquals(0, WitnessChecks.daysAfter(LocalDate.of(2026, 10, 17), 1_792_222_800_000, zone))
        assertEquals(3, WitnessChecks.daysAfter(LocalDate.of(2026, 10, 14), 1_792_222_800_000, zone))
        assertFalse(WitnessChecks.daysAfter(LocalDate.of(2026, 10, 20), 1_792_222_800_000, zone) < 0)
    }
}
