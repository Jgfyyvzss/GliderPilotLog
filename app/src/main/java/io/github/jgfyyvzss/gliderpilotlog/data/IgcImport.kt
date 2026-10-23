package io.github.jgfyyvzss.gliderpilotlog.data

import android.content.Context
import android.net.Uri
import io.github.jgfyyvzss.gliderpilotlog.core.Msg
import io.github.jgfyyvzss.gliderpilotlog.core.MsgKey
import io.github.jgfyyvzss.gliderpilotlog.core.igc.IgcFlights
import io.github.jgfyyvzss.gliderpilotlog.core.igc.IgcParser
import io.github.jgfyyvzss.gliderpilotlog.core.igc.IgcRead
import io.github.jgfyyvzss.gliderpilotlog.core.igc.IgcSegment
import io.github.jgfyyvzss.gliderpilotlog.core.igc.StoredIgc
import io.github.jgfyyvzss.gliderpilotlog.core.msg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface IgcImportState {
    data object Reading : IgcImportState
    data class Failed(val message: Msg) : IgcImportState
    /** The stored file's flying segments and which of them have been saved as flights. */
    data class Ready(val fileId: String, val gliderIdHeader: String?, val segments: List<IgcSegment>, val imported: Set<Int> = emptySet()) : IgcImportState
}

/**
 * The IGC file being logged, from the file picker, another app's share, a logger download, or a file already
 * received (from the upload screen). Every file goes into [IgcFiles] first, which records the flights logged from it.
 */
class IgcImportStore(private val context: Context, private val scope: CoroutineScope, private val files: IgcFiles) {
    private val _state = MutableStateFlow<IgcImportState?>(null)
    val state: StateFlow<IgcImportState?> = _state

    private val opened = Channel<Unit>(Channel.CONFLATED)
    /** Fires each time a file is opened, so the UI can show the import screen. */
    val openRequests = opened.receiveAsFlow()

    fun open(uri: Uri) = start { files.add(context.contentResolver, uri) }

    /** A file downloaded from a logger. */
    fun openDownloaded(name: String, bytes: ByteArray) = start { files.add(name, bytes) }

    /** A file already received, for example from the upload screen's Log flight. */
    fun openStored(id: String) = start { files.get(id) }

    private fun start(find: suspend () -> StoredIgc?) {
        _state.value = IgcImportState.Reading
        opened.trySend(Unit)
        scope.launch { _state.value = find()?.let { load(it) } ?: IgcImportState.Failed(msg(MsgKey.IGC_UNREADABLE)) }
    }

    /** Records a saved flight against its segment, and its glider for the WeGlide upload. */
    fun markImported(index: Int, gliderId: String?) {
        val ready = _state.value as? IgcImportState.Ready ?: return
        _state.update { s -> if (s is IgcImportState.Ready) s.copy(imported = s.imported + index) else s }
        scope.launch { files.update(ready.fileId) { it.copy(logged = it.logged + index, loggedGliderId = gliderId ?: it.loggedGliderId) } }
    }

    fun segment(index: Int): Pair<IgcSegment, String?>? =
        (_state.value as? IgcImportState.Ready)?.let { s -> s.segments.getOrNull(index)?.let { it to s.gliderIdHeader } }

    fun clear() { _state.value = null }

    private suspend fun load(entry: StoredIgc): IgcImportState = withContext(Dispatchers.Default) {
        val bytes = files.bytes(entry.id) ?: return@withContext IgcImportState.Failed(msg(MsgKey.IGC_UNREADABLE))
        when (val r = IgcParser.parse(bytes.toString(Charsets.ISO_8859_1))) {
            is IgcRead.Refused -> IgcImportState.Failed(r.message)
            is IgcRead.Ok -> {
                val segments = IgcFlights.segments(r.track)
                if (segments.isEmpty()) IgcImportState.Failed(msg(MsgKey.IGC_NO_FLIGHT))
                else IgcImportState.Ready(entry.id, r.track.gliderId, segments, entry.logged)
            }
        }
    }
}
