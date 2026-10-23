package io.github.jgfyyvzss.soarlog.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PicNameTest {
    private val profile = "Pat Pilot"

    @Test fun initialValueFollowsRole() {
        assertEquals(profile, PicName.initial(Role.PIC, profile))
        assertEquals(profile, PicName.initial(Role.INSTRUCTOR, profile))
        assertEquals(profile, PicName.initial(Role.COACH, profile))
        assertEquals("", PicName.initial(Role.STUDENT, profile))
        assertEquals("", PicName.initial(Role.MUTUAL, profile))
    }

    @Test fun noProfileNameStartsBlank() {
        Role.entries.forEach { assertEquals("", PicName.initial(it, "")) }
    }

    @Test fun switchingToStudentOrMutualClearsProfileName() {
        assertEquals("", PicName.afterRoleChange(profile, Role.STUDENT, profile))
        assertEquals("", PicName.afterRoleChange(profile, Role.MUTUAL, profile))
    }

    @Test fun switchingToStudentKeepsATypedName() {
        assertEquals("Sam Instructor", PicName.afterRoleChange("Sam Instructor", Role.STUDENT, profile))
    }

    @Test fun switchingToPicFillsOnlyABlankField() {
        assertEquals(profile, PicName.afterRoleChange("", Role.PIC, profile))
        assertEquals(profile, PicName.afterRoleChange("  ", Role.COACH, profile))
        assertEquals("Sam Instructor", PicName.afterRoleChange("Sam Instructor", Role.INSTRUCTOR, profile))
    }

    @Test fun blankProfileNeverFillsOrClears() {
        assertEquals("", PicName.afterRoleChange("", Role.PIC, ""))
        assertEquals("Someone", PicName.afterRoleChange("Someone", Role.STUDENT, ""))
    }
}
