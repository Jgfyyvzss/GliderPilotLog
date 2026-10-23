package io.github.jgfyyvzss.soarlog.core

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
    /** Another live flight holding the same log number, as a [MsgKey.FLIGHT_ON] message, or null if it is free. */
    val logNoHeldBy: Msg?,
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
    data class Valid(val flight: ValidFlight, val warnings: List<Msg>) : FlightValidation
    data class Invalid(val errors: Map<FlightField, Msg>, val warnings: List<Msg>) : FlightValidation
}

object FlightRules {
    /** Warn when the entered duration and the launch-to-landing time differ by more than this. */
    const val TIMES_TOLERANCE_MINUTES = 1

    fun parseLogNo(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it > 0 }

    /** Minutes between the typed launch and landing times, when both are valid and landing is later. */
    fun durationFromTimes(launchText: String, landingText: String): Int? {
        val launch = ClockTime.parseFormEntry(launchText) ?: return null
        val landing = ClockTime.parseFormEntry(landingText) ?: return null
        return ClockTime.minutesBetween(launch, landing)
    }

    fun validate(draft: FlightDraft, context: FlightContext): FlightValidation {
        val errors = linkedMapOf<FlightField, Msg>()
        val warnings = mutableListOf<Msg>()

        val logNo = parseLogNo(draft.logNoText)
        when {
            logNo == null -> errors[FlightField.LOG_NO] = msg(MsgKey.LOG_NO_POSITIVE)
            context.logNoHeldBy != null -> errors[FlightField.LOG_NO] = msg(MsgKey.LOG_NO_USED, logNo, context.logNoHeldBy)
        }

        if (draft.date == null) errors[FlightField.DATE] = msg(MsgKey.DATE_REQUIRED)

        val duration = Durations.parseFormEntry(draft.durationText)
        when {
            duration == null -> errors[FlightField.DURATION] = msg(MsgKey.DURATION_FORMAT)
            !Durations.isValidFlightDuration(duration) -> errors[FlightField.DURATION] = msg(MsgKey.DURATION_RANGE)
        }

        if (draft.gliderId == null) errors[FlightField.GLIDER] = msg(MsgKey.GLIDER_REQUIRED)
        if (draft.airfieldId == null) errors[FlightField.AIRFIELD] = msg(MsgKey.AIRFIELD_REQUIRED)
        if (draft.airfieldId != null && context.airfieldClubCount > 0 && draft.clubId == null) {
            errors[FlightField.CLUB] = msg(MsgKey.CLUB_REQUIRED)
        }
        if (draft.launchTypeId == null) errors[FlightField.LAUNCH_TYPE] = msg(MsgKey.LAUNCH_TYPE_REQUIRED)

        val pic = draft.picName.trim()
        if (pic.isEmpty()) {
            errors[FlightField.PIC_NAME] =
                msg(if (draft.role.picIsSomeoneElse) MsgKey.PIC_OTHER_REQUIRED else MsgKey.PIC_REQUIRED)
        }

        val launch = parseOptionalTime(draft.launchTimeText, FlightField.LAUNCH_TIME, errors)
        val landing = parseOptionalTime(draft.landingTimeText, FlightField.LANDING_TIME, errors)
        if (launch != null && landing != null) {
            val between = ClockTime.minutesBetween(launch, landing)
            if (between == null) {
                errors[FlightField.LANDING_TIME] = msg(MsgKey.LANDING_AFTER_LAUNCH)
            } else if (duration != null && kotlin.math.abs(between - duration) > TIMES_TOLERANCE_MINUTES) {
                warnings += msg(MsgKey.TIMES_DISAGREE, Durations.formatHm(duration), Durations.formatHm(between))
            }
        }

        var landingAirfieldId: String? = null
        var landingPlace: String? = null
        when (draft.landingChoice) {
            LandingChoice.SAME_AS_DEPARTURE -> Unit
            LandingChoice.AIRFIELD ->
                if (draft.landingAirfieldId == null) errors[FlightField.LANDING_AIRFIELD] = msg(MsgKey.LANDING_AIRFIELD_REQUIRED)
                else landingAirfieldId = draft.landingAirfieldId
            LandingChoice.OTHER ->
                if (draft.landingPlace.isBlank()) errors[FlightField.LANDING_PLACE] = msg(MsgKey.LANDING_PLACE_REQUIRED)
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

    private fun parseOptionalTime(text: String, field: FlightField, errors: MutableMap<FlightField, Msg>): ClockTime? {
        if (text.isBlank()) return null
        return ClockTime.parseFormEntry(text) ?: run {
            errors[field] = msg(MsgKey.TIME_FORMAT)
            null
        }
    }
}
