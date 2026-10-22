package io.github.jgfyyvzss.gliderpilotlog.ui.weglide

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jgfyyvzss.gliderpilotlog.core.Msg
import io.github.jgfyyvzss.gliderpilotlog.core.MsgKey
import io.github.jgfyyvzss.gliderpilotlog.core.msg
import io.github.jgfyyvzss.gliderpilotlog.core.igc.IgcFlights
import io.github.jgfyyvzss.gliderpilotlog.core.igc.IgcMatching
import io.github.jgfyyvzss.gliderpilotlog.core.igc.IgcParser
import io.github.jgfyyvzss.gliderpilotlog.core.igc.IgcRead
import io.github.jgfyyvzss.gliderpilotlog.core.igc.IgcSegment
import io.github.jgfyyvzss.gliderpilotlog.core.weglide.UploadedFlight
import io.github.jgfyyvzss.gliderpilotlog.core.weglide.WeGlideAircraft
import io.github.jgfyyvzss.gliderpilotlog.core.weglide.WeGlideCredentials
import io.github.jgfyyvzss.gliderpilotlog.core.weglide.WeGlideUploadResponse
import io.github.jgfyyvzss.gliderpilotlog.data.GliderEntity
import io.github.jgfyyvzss.gliderpilotlog.data.LogbookRepository
import io.github.jgfyyvzss.gliderpilotlog.data.weglide.AircraftCatalog
import io.github.jgfyyvzss.gliderpilotlog.data.weglide.ApiResult
import io.github.jgfyyvzss.gliderpilotlog.data.weglide.CredentialStore
import io.github.jgfyyvzss.gliderpilotlog.data.weglide.IgcFile
import io.github.jgfyyvzss.gliderpilotlog.data.weglide.PendingUpload
import io.github.jgfyyvzss.gliderpilotlog.data.weglide.WeGlideApi
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** What the file contains, for the pilot to check before uploading. */
data class FileSummary(val gliderIdHeader: String?, val first: IgcSegment?, val flights: Int)

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
 * Upload to WeGlide, on its own: any IGC file, with or without a logbook entry. The upload gate from Appendix E is
 * kept by prompting on this screen for anything missing: the file, the WeGlide aircraft and the pilot's WeGlide ID
 * and date of birth.
 */
class UploadViewModel(
    private val repo: LogbookRepository,
    private val catalog: AircraftCatalog,
    private val api: WeGlideApi,
    private val store: CredentialStore,
    private val pending: PendingUpload,
) : ViewModel() {
    var file by mutableStateOf<IgcFile?>(null)
        private set
    var summary by mutableStateOf<FileSummary?>(null)
        private set
    /** The file could not be read as IGC. */
    var fileProblem by mutableStateOf<Msg?>(null)
        private set
    var aircraft by mutableStateOf<AircraftChoice?>(null)
        private set
    /** The glider in the logbook that matches the file's header, used to start the aircraft search. */
    var matchedGlider by mutableStateOf<GliderEntity?>(null)
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
            pending.file.collect { f -> if (f != null && f !== file) useFile(f) }
        }
    }

    private suspend fun useFile(f: IgcFile) {
        file = f
        status = UploadStatus.Idle
        aircraft = null
        matchedGlider = null
        when (val r = IgcParser.parse(f.bytes.toString(Charsets.ISO_8859_1))) {
            is IgcRead.Refused -> {
                summary = null
                fileProblem = r.message
            }
            is IgcRead.Ok -> {
                fileProblem = null
                val segments = IgcFlights.segments(r.track)
                summary = FileSummary(r.track.gliderId, segments.firstOrNull(), segments.size)
                val glider = IgcMatching.glider(r.track.gliderId, repo.observeGliders().first().filter { !it.archived }) { it.registrationKey }
                matchedGlider = glider
                val id = glider?.weglideAircraftId
                if (id != null) aircraft = AircraftChoice(id, catalog.find(id)?.label ?: id.toString(), glider)
            }
        }
    }

    /**
     * Uses the picked WeGlide aircraft for this upload. When the file matched a logbook glider without a WeGlide
     * type, the pick is stored on that glider too, as on the glider screen.
     */
    fun pickAircraft(picked: WeGlideAircraft) = viewModelScope.launch {
        val glider = matchedGlider?.takeIf { it.weglideAircraftId == null }
        if (glider != null) repo.setWeGlideAircraft(glider.id, picked)
        aircraft = AircraftChoice(picked.id, picked.label, glider)
        if (glider != null) matchedGlider = repo.glider(glider.id)
    }

    fun editCredentials() { editingCredentials = true }

    /** Saves the WeGlide ID and date of birth, encrypted, and uses them from now on. */
    fun saveCredentials(userId: String, dateOfBirth: LocalDate) = viewModelScope.launch {
        val c = WeGlideCredentials(userId.trim(), dateOfBirth)
        store.save(c)
        credentials = c
        editingCredentials = false
    }

    val canUpload get() = file != null && fileProblem == null && aircraft != null && credentials != null && !editingCredentials &&
        status != UploadStatus.Uploading

    fun upload() {
        val f = file ?: return
        val a = aircraft ?: return
        val c = credentials ?: return
        status = UploadStatus.Uploading
        viewModelScope.launch {
            status = when (val r = api.uploadIgc(f.bytes, f.name, c, a.aircraftId)) {
                is ApiResult.Ok -> {
                    // Done with the file: it is not kept anywhere.
                    pending.clear()
                    file = null
                    UploadStatus.Done(WeGlideUploadResponse.parse(r.value))
                }
                is ApiResult.Failed -> UploadStatus.Failed(r.message)
            }
        }
    }

    /** The picked file could not be read at all. */
    fun unreadable() {
        file = null
        summary = null
        fileProblem = msg(MsgKey.IGC_UNREADABLE)
    }

    /** Starts again with no file, after a finished upload. */
    fun reset() {
        pending.clear()
        file = null
        summary = null
        fileProblem = null
        aircraft = null
        matchedGlider = null
        status = UploadStatus.Idle
    }

    override fun onCleared() {
        pending.clear()
    }
}
