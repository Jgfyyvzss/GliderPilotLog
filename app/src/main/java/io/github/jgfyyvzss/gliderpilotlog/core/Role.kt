package io.github.jgfyyvzss.gliderpilotlog.core

/** Every flight lands in exactly one role bucket. Stored as the enum name. */
enum class Role(val label: String) {
    PIC("PIC"),
    STUDENT("Student"),
    INSTRUCTOR("Instructor"),
    COACH("Coach"),
    MUTUAL("Mutual");

    val isDual: Boolean get() = this != PIC

    /** The pilot in command is someone else, so the profile name is not the default. */
    val picIsSomeoneElse: Boolean get() = this == STUDENT || this == MUTUAL

    companion object {
        val dualRoles = listOf(STUDENT, INSTRUCTOR, COACH, MUTUAL)
    }
}

/** Rules for the pilot-in-command field on the flight form. */
object PicName {
    /** The initial value for a new flight with the given role. */
    fun initial(role: Role, profileName: String): String =
        if (role.picIsSomeoneElse) "" else profileName.trim()

    /**
     * The value after the role changes. Switching to Student or Mutual clears the field if it still holds
     * the profile name; switching to PIC, Instructor or Coach fills a blank field. A typed name is never replaced.
     */
    fun afterRoleChange(current: String, newRole: Role, profileName: String): String {
        val profile = profileName.trim()
        return if (newRole.picIsSomeoneElse) {
            if (profile.isNotEmpty() && current.trim() == profile) "" else current
        } else {
            if (current.isBlank()) profile else current
        }
    }
}
