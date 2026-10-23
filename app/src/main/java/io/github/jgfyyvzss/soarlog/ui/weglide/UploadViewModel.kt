package io.github.jgfyyvzss.soarlog.ui.weglide

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jgfyyvzss.soarlog.core.Msg
import io.github.jgfyyvzss.soarlog.core.MsgKey
import io.github.jgfyyvzss.soarlog.core.igc.IgcMatching
import io.github.jgfyyvzss.soarlog.core.igc.StoredIgc
import io.github.jgfyyvzss.soarlog.core.msg
import io.github.jgfyyvzss.soarlog.core.weglide.UploadAircraft
import io.github.jgfyyvzss.soarlog.core.weglide.UploadedFlight
import io.github.jgfyyvzss.soarlog.core.weglide.WeGlideAircraft
import io.github.jgfyyvzss.soarlog.core.weglide.WeGlideCredentials
import io.github.jgfyyvzss.soarlog.core.weglide.WeGlideUploadResponse
import io.github.jgfyyvzss.soarlog.data.GliderEntity
import io.github.jgfyyvzss.soarlog.data.IgcFiles
import io.github.jgfyyvzss.soarlog.data.LogbookRepository
import io.github.jgfyyvzss.soarlog.data.weglide.AircraftCatalog
import io.github.jgfyyvzss.soarlog.data.weglide.ApiResult
import io.github.jgfyyvzss.soarlog.data.weglide.CredentialStore
import io.github.jgfyyvzss.soarlog.data.weglide.UploadSelection
import io.github.jgfyyvzss.soarlog.data.weglide.WeGlideApi
import java.time.LocalDate
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** The WeGlide aircraft for this upload, and the logbook glider it came from, if any. */
data class AircraftChoice(val aircraftId: Long, val label: String, val glider: GliderEntity?)

sealed interface UploadStatus {
    data object Idle : UploadStatus
    data object Uploading : UploadStatus
    /** [flight] is null when WeGlide accepted the file but its reply could not be read. */
    data class Done(val flight: UploadedFlight?) : UploadStatus
    data class Failed(val message: Msg) : UploadStatus
}

/**
 * Upload to WeGlide, for any received IGC file, with or without a logbook entry. The upload gate from Appendix E is
 * kept by prompting on this screen for anything missing: the file, the WeGlide aircraft, and the pilot's WeGlide ID
 * and date of birth. The file's logbook status is shown too, with a way to log it, so neither job is forgotten.
 */
class UploadViewModel(
    private val repo: LogbookRepository,
    private val catalog: AircraftCatalog,
    private val api: WeGlideApi,
    private val store: CredentialStore,
    private val igcFiles: IgcFiles,
    private val selection: UploadSelection,
) : ViewModel() {
    /** Every received file, newest first, with live statuses. */
    val files: StateFlow<List<StoredIgc>> = igcFiles.files
    /** The chosen file's id; its entry is looked up in [files] so its statuses stay current. */
    var fileId by mutableStateOf<String?>(null)
        private set
    /** The file could not be read. */
    var fileProblem by mutableStateOf<Msg?>(null)
        private set
    var aircraft by mutableStateOf<AircraftChoice?>(null)
        private set
    /** The glider that supplies, or should be given, the WeGlide aircraft. */
    var glider by mutableStateOf<GliderEntity?>(null)
        private set
    var credentials by mutableStateOf<WeGlideCredentials?>(null)
        private set
    var editingCredentials by mutableStateOf(false)
        private set
    /** False until the saved credentials have been read, so the screen does not flash the wrong state. */
    var credentialsLoaded by mutableStateOf(false)
        private set
    var status by mutableStateOf<UploadStatus>(UploadStatus.Idle)
        private set

    init {
        viewModelScope.launch {
            credentials = store.load()
            editingCredentials = credentials == null
            credentialsLoaded = true
            catalog.load()
        }
        viewModelScope.launch {
            selection.selected.collect { s -> if (s != null) choose(s.fileId) }
        }
    }

    /** Shows a received file, ready to upload once the aircraft and credentials are known. */
    fun choose(id: String) = viewModelScope.launch {
        val entry = igcFiles.get(id) ?: run { unreadable(); return@launch }
        fileId = entry.id
        fileProblem = null
        status = UploadStatus.Idle
        val gliders = repo.observeGliders().first()
        val saved = entry.loggedGliderId?.let { g -> gliders.firstOrNull { it.id == g } }
        val matched = IgcMatching.glider(entry.gliderIdHeader, gliders.filter { !it.archived }) { it.registrationKey }
        val g = UploadAircraft.choose(saved, matched) { it.weglideAircraftId }
        glider = g
        aircraft = g?.weglideAircraftId?.let { id -> AircraftChoice(id, catalog.find(id)?.label ?: id.toString(), g) }
    }

    /**
     * Uses the picked WeGlide aircraft for this upload. When the glider has no WeGlide type yet, the pick is stored
     * on it too, as on the glider screen.
     */
    fun pickAircraft(picked: WeGlideAircraft) = viewModelScope.launch {
        val g = glider?.takeIf { it.weglideAircraftId == null }
        if (g != null) {
            repo.setWeGlideAircraft(g.id, picked)
            glider = repo.glider(g.id)
        }
        aircraft = AircraftChoice(picked.id, picked.label, glider)
    }

    fun editCredentials() { editingCredentials = true }

    /** Saves the WeGlide ID and date of birth, encrypted, and uses them from now on. */
    fun saveCredentials(userId: String, dateOfBirth: LocalDate) = viewModelScope.launch {
        val c = WeGlideCredentials(userId.trim(), dateOfBirth)
        store.save(c)
        credentials = c
        editingCredentials = false
    }

    fun canUpload(entry: StoredIgc?) = entry != null && !entry.uploaded && aircraft != null && credentials != null &&
        !editingCredentials && status != UploadStatus.Uploading

    fun upload() {
        val id = fileId ?: return
        val a = aircraft ?: return
        val c = credentials ?: return
        status = UploadStatus.Uploading
        viewModelScope.launch {
            val entry = igcFiles.get(id)
            val bytes = igcFiles.bytes(id)
            if (entry == null || bytes == null) {
                status = UploadStatus.Failed(msg(MsgKey.IGC_UNREADABLE))
                return@launch
            }
            status = when (val r = api.uploadIgc(bytes, entry.name, c, a.aircraftId)) {
                is ApiResult.Ok -> {
                    val flight = WeGlideUploadResponse.parse(r.value)
                    // Marked as uploaded, so it is not offered again; the file stays until it expires, for logging.
                    igcFiles.update(id) { it.copy(uploaded = true, weglideFlightId = flight?.id) }
                    UploadStatus.Done(flight)
                }
                is ApiResult.Failed -> UploadStatus.Failed(r.message)
            }
        }
    }

    /** Deletes the chosen file from the phone. */
    fun remove() {
        val id = fileId ?: return
        viewModelScope.launch {
            igcFiles.remove(id)
            close()
        }
    }

    /** The picked file could not be read at all. */
    fun unreadable() {
        fileId = null
        fileProblem = msg(MsgKey.IGC_UNREADABLE)
    }

    /** Back to the list of received files. */
    fun close() {
        selection.clear()
        fileId = null
        fileProblem = null
        aircraft = null
        glider = null
        status = UploadStatus.Idle
    }
}
