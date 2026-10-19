package io.github.jgfyyvzss.gliderpilotlog.ui.flights

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jgfyyvzss.gliderpilotlog.core.Durations
import io.github.jgfyyvzss.gliderpilotlog.core.FlightDraft
import io.github.jgfyyvzss.gliderpilotlog.core.FlightField
import io.github.jgfyyvzss.gliderpilotlog.core.FlightRules
import io.github.jgfyyvzss.gliderpilotlog.core.FlightValidation
import io.github.jgfyyvzss.gliderpilotlog.core.LandingChoice
import io.github.jgfyyvzss.gliderpilotlog.core.PicName
import io.github.jgfyyvzss.gliderpilotlog.core.Role
import io.github.jgfyyvzss.gliderpilotlog.data.ClubEntity
import io.github.jgfyyvzss.gliderpilotlog.data.FlightEntity
import io.github.jgfyyvzss.gliderpilotlog.data.LogbookRepository
import io.github.jgfyyvzss.gliderpilotlog.data.Settings
import io.github.jgfyyvzss.gliderpilotlog.data.SettingsRepository
import java.time.LocalDate
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class FlightEditViewModel(
    private val repo: LogbookRepository,
    settingsRepo: SettingsRepository,
    private val id: String?,
) : ViewModel() {
    var draft by mutableStateOf(FlightDraft())
        private set
    var errors by mutableStateOf<Map<FlightField, String>>(emptyMap())
        private set
    var warnings by mutableStateOf<List<String>>(emptyList())
        private set
    /** The clubs linked to the chosen airfield. */
    var airfieldClubs by mutableStateOf<List<ClubEntity>>(emptyList())
        private set
    var loaded by mutableStateOf(false)
        private set
    var done by mutableStateOf(false)
        private set
    /** Set once the pilot has seen the warnings, so the next save goes ahead. */
    private var warningsShownFor: FlightDraft? = null
    private var profileName = ""

    val isNew get() = id == null

    val settings = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())
    val gliders = repo.observeGliders().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val airfields = repo.observeAirfields().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val launchTypes = repo.observeLaunchTypes().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch {
            profileName = settingsRepo.settings.first().pilotName
            draft = if (id == null) {
                FlightDraft(
                    logNoText = repo.nextLogNo().toString(),
                    date = LocalDate.now(),
                    role = Role.PIC,
                    picName = PicName.initial(Role.PIC, profileName),
                )
            } else {
                repo.flight(id)?.toDraft() ?: FlightDraft()
            }
            draft.airfieldId?.let { airfieldClubs = repo.clubsFor(it) }
            loaded = true
        }
    }

    private fun FlightEntity.toDraft() = FlightDraft(
        logNoText = logNo.toString(),
        date = LocalDate.parse(date),
        durationText = Durations.formatHm(durationMinutes),
        gliderId = gliderId,
        airfieldId = airfieldId,
        clubId = clubId,
        launchTypeId = launchTypeId,
        role = Role.valueOf(role),
        picName = picName,
        launchTimeText = launchTime ?: "",
        landingTimeText = landingTime ?: "",
        landingChoice = when {
            landingAirfieldId != null -> LandingChoice.AIRFIELD
            landingPlace != null -> LandingChoice.OTHER
            else -> LandingChoice.SAME_AS_DEPARTURE
        },
        landingAirfieldId = landingAirfieldId,
        landingPlace = landingPlace ?: "",
        notes = notes ?: "",
    )

    private fun edit(field: FlightField?, change: (FlightDraft) -> FlightDraft) {
        draft = change(draft)
        if (field != null) errors = errors - field
        warnings = emptyList()
    }

    fun setLogNo(text: String) = edit(FlightField.LOG_NO) { it.copy(logNoText = text.filter(Char::isDigit)) }
    fun setDate(date: LocalDate) = edit(FlightField.DATE) { it.copy(date = date) }
    fun setDuration(text: String) = edit(FlightField.DURATION) { it.copy(durationText = text) }
    fun setGlider(id: String) = edit(FlightField.GLIDER) { it.copy(gliderId = id) }
    fun setClub(id: String) = edit(FlightField.CLUB) { it.copy(clubId = id) }
    fun setLaunchType(id: String) = edit(FlightField.LAUNCH_TYPE) { it.copy(launchTypeId = id) }
    fun setPicName(text: String) = edit(FlightField.PIC_NAME) { it.copy(picName = text) }
    fun setNotes(text: String) = edit(null) { it.copy(notes = text) }
    fun setLandingChoice(choice: LandingChoice) = edit(null) { it.copy(landingChoice = choice) }
        .also { errors = errors - FlightField.LANDING_AIRFIELD - FlightField.LANDING_PLACE }
    fun setLandingAirfield(id: String) = edit(FlightField.LANDING_AIRFIELD) { it.copy(landingAirfieldId = id) }
    fun setLandingPlace(text: String) = edit(FlightField.LANDING_PLACE) { it.copy(landingPlace = text) }

    /** The club is set silently when the airfield has exactly one, and offered when it has more. */
    fun setAirfield(id: String) {
        edit(FlightField.AIRFIELD) { it.copy(airfieldId = id) }
        viewModelScope.launch {
            val clubs = repo.clubsFor(id)
            airfieldClubs = clubs
            val keep = draft.clubId?.takeIf { c -> clubs.any { it.id == c } }
            draft = draft.copy(clubId = if (clubs.size == 1) clubs.single().id else keep)
            errors = errors - FlightField.CLUB
        }
    }

    fun setRole(role: Role) = edit(FlightField.PIC_NAME) {
        it.copy(role = role, picName = PicName.afterRoleChange(it.picName, role, profileName))
    }

    /** When both times are valid, the duration is pre-filled from them and can still be edited. */
    fun setLaunchTime(text: String) = setTimes(text, draft.landingTimeText, FlightField.LAUNCH_TIME)
    fun setLandingTime(text: String) = setTimes(draft.launchTimeText, text, FlightField.LANDING_TIME)

    private fun setTimes(launch: String, landing: String, field: FlightField) {
        edit(field) { it.copy(launchTimeText = launch, landingTimeText = landing) }
        errors = errors - FlightField.LAUNCH_TIME - FlightField.LANDING_TIME
        FlightRules.durationFromTimes(launch, landing)?.let { minutes ->
            draft = draft.copy(durationText = Durations.formatHm(minutes))
            errors = errors - FlightField.DURATION
        }
    }

    fun save() = viewModelScope.launch {
        val check = repo.validateFlight(id, draft)
        if (check is FlightValidation.Invalid) {
            errors = check.errors
            warnings = check.warnings
            return@launch
        }
        check as FlightValidation.Valid
        if (check.warnings.isNotEmpty() && warningsShownFor != draft) {
            warnings = check.warnings
            warningsShownFor = draft
            return@launch
        }
        val (result, _) = repo.saveFlight(id, draft)
        when (result) {
            is FlightValidation.Valid -> done = true
            is FlightValidation.Invalid -> { errors = result.errors; warnings = result.warnings }
        }
    }

    fun delete() = viewModelScope.launch {
        id?.let { repo.deleteFlight(it) }
        done = true
    }
}
