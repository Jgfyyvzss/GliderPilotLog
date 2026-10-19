package io.github.jgfyyvzss.gliderpilotlog.core

import java.time.LocalDate

/** Where the flight ended, when the Record landing place setting is on. */
enum class LandingChoice { SAME_AS_DEPARTURE, AIRFIELD, OTHER }

/** The flight form as typed, before validation. */
data class FlightDraft(
    val logNoText: String = "",
    val date: LocalDate? = null,
    val durationText: String = "",
    val gliderId: String? = null,
    val airfieldId: String? = null,
    val clubId: String? = null,
    val launchTypeId: String? = null,
    val role: Role = Role.PIC,
    val picName: String = "",
    val launchTimeText: String = "",
    val landingTimeText: String = "",
    val landingChoice: LandingChoice = LandingChoice.SAME_AS_DEPARTURE,
    val landingAirfieldId: String? = null,
    val landingPlace: String = "",
    val notes: String = "",
)

enum class FlightField {
    LOG_NO, DATE, DURATION, GLIDER, AIRFIELD, CLUB, LAUNCH_TYPE, PIC_NAME,
    LAUNCH_TIME, LANDING_TIME, LANDING_AIRFIELD, LANDING_PLACE,
}

/** Facts from the database that validation needs. */
data class FlightContext(
    /** Number of clubs linked to the chosen airfield. */
    val airfieldClubCount: Int,
    /** A short description of another live flight holding the same log number, or null if it is free. */
    val logNoHeldBy: String?,
)

/** A draft that passed validation, in stored form. */
data class ValidFlight(
    val logNo: Int,
    val date: LocalDate,
    val durationMinutes: Int,
    val gliderId: String,
    val airfieldId: String,
    val clubId: String?,
    val launchTypeId: String,
    val role: Role,
    val picName: String,
    val launchTime: ClockTime?,
    val landingTime: ClockTime?,
    val landingAirfieldId: String?,
    val landingPlace: String?,
    val notes: String?,
)

sealed interface FlightValidation {
    data class Valid(val flight: ValidFlight, val warnings: List<String>) : FlightValidation
    data class Invalid(val errors: Map<FlightField, String>, val warnings: List<String>) : FlightValidation
}

object FlightRules {
    /** Warn when the entered duration and the launch-to-landing time differ by more than this. */
    const val TIMES_TOLERANCE_MINUTES = 1

    fun parseLogNo(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it > 0 }

    /** Minutes between the typed launch and landing times, when both are valid and landing is later. */
    fun durationFromTimes(launchText: String, landingText: String): Int? {
        val launch = ClockTime.parse(launchText) ?: return null
        val landing = ClockTime.parse(landingText) ?: return null
        return ClockTime.minutesBetween(launch, landing)
    }

    fun validate(draft: FlightDraft, context: FlightContext): FlightValidation {
        val errors = linkedMapOf<FlightField, String>()
        val warnings = mutableListOf<String>()

        val logNo = parseLogNo(draft.logNoText)
        when {
            logNo == null -> errors[FlightField.LOG_NO] = "Enter a whole number above 0"
            context.logNoHeldBy != null -> errors[FlightField.LOG_NO] = "Log number $logNo is already used by ${context.logNoHeldBy}"
        }

        if (draft.date == null) errors[FlightField.DATE] = "Choose the flight date"

        val duration = Durations.parseHm(draft.durationText)
        when {
            duration == null -> errors[FlightField.DURATION] = "Enter the duration as H:MM"
            !Durations.isValidFlightDuration(duration) -> errors[FlightField.DURATION] =
                "Duration must be between 0:01 and 24:00"
        }

        if (draft.gliderId == null) errors[FlightField.GLIDER] = "Choose a glider"
        if (draft.airfieldId == null) errors[FlightField.AIRFIELD] = "Choose an airfield"
        if (draft.airfieldId != null && context.airfieldClubCount > 0 && draft.clubId == null) {
            errors[FlightField.CLUB] = "Choose a club"
        }
        if (draft.launchTypeId == null) errors[FlightField.LAUNCH_TYPE] = "Choose a launch type"

        val pic = draft.picName.trim()
        if (pic.isEmpty()) {
            errors[FlightField.PIC_NAME] =
                if (draft.role.picIsSomeoneElse) "Enter the name of the pilot in command" else "Enter the pilot in command"
        }

        val launch = parseOptionalTime(draft.launchTimeText, FlightField.LAUNCH_TIME, errors)
        val landing = parseOptionalTime(draft.landingTimeText, FlightField.LANDING_TIME, errors)
        if (launch != null && landing != null) {
            val between = ClockTime.minutesBetween(launch, landing)
            if (between == null) {
                errors[FlightField.LANDING_TIME] = "Landing must be after launch"
            } else if (duration != null && kotlin.math.abs(between - duration) > TIMES_TOLERANCE_MINUTES) {
                warnings += "The duration ${Durations.formatHm(duration)} differs from launch to landing (${Durations.formatHm(between)})"
            }
        }

        var landingAirfieldId: String? = null
        var landingPlace: String? = null
        when (draft.landingChoice) {
            LandingChoice.SAME_AS_DEPARTURE -> Unit
            LandingChoice.AIRFIELD ->
                if (draft.landingAirfieldId == null) errors[FlightField.LANDING_AIRFIELD] = "Choose the landing airfield"
                else landingAirfieldId = draft.landingAirfieldId
            LandingChoice.OTHER ->
                if (draft.landingPlace.isBlank()) errors[FlightField.LANDING_PLACE] = "Describe where the flight ended"
                else landingPlace = draft.landingPlace.trim()
        }

        if (errors.isNotEmpty()) return FlightValidation.Invalid(errors, warnings)

        return FlightValidation.Valid(
            ValidFlight(
                logNo = logNo!!,
                date = draft.date!!,
                durationMinutes = duration!!,
                gliderId = draft.gliderId!!,
                airfieldId = draft.airfieldId!!,
                clubId = draft.clubId.takeIf { context.airfieldClubCount > 0 },
                launchTypeId = draft.launchTypeId!!,
                role = draft.role,
                picName = pic,
                launchTime = launch,
                landingTime = landing,
                landingAirfieldId = landingAirfieldId,
                landingPlace = landingPlace,
                notes = draft.notes.trim().ifEmpty { null },
            ),
            warnings,
        )
    }

    private fun parseOptionalTime(text: String, field: FlightField, errors: MutableMap<FlightField, String>): ClockTime? {
        if (text.isBlank()) return null
        return ClockTime.parse(text) ?: run {
            errors[field] = "Enter a 24-hour time as HH:MM"
            null
        }
    }
}
