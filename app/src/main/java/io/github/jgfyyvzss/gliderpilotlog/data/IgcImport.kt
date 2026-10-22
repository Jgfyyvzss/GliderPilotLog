package io.github.jgfyyvzss.gliderpilotlog.data

import android.content.Context
import android.net.Uri
import io.github.jgfyyvzss.gliderpilotlog.core.Msg
import io.github.jgfyyvzss.gliderpilotlog.core.MsgKey
import io.github.jgfyyvzss.gliderpilotlog.core.igc.IgcFlights
import io.github.jgfyyvzss.gliderpilotlog.core.igc.IgcParser
import io.github.jgfyyvzss.gliderpilotlog.core.igc.IgcRead
import io.github.jgfyyvzss.gliderpilotlog.core.igc.IgcSegment
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
    /** The file's flying segments and which of them have been saved as flights. */
    data class Ready(val gliderIdHeader: String?, val segments: List<IgcSegment>, val imported: Set<Int> = emptySet()) : IgcImportState
}

/**
 * The IGC file being imported, from the file picker or another app's share. The file is read once into memory and
 * only its segments are kept, here and nowhere else; nothing is written to storage, and [clear] drops them.
 */
class IgcImportStore(private val context: Context, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<IgcImportState?>(null)
    val state: StateFlow<IgcImportState?> = _state

    private val opened = Channel<Unit>(Channel.CONFLATED)
    /** Fires each time a file is opened, so the UI can show the import screen. */
    val openRequests = opened.receiveAsFlow()

    fun open(uri: Uri) {
        _state.value = IgcImportState.Reading
        opened.trySend(Unit)
        scope.launch { _state.value = read(uri) }
    }

    /** A file downloaded from a logger, already in memory. Its bytes are analysed as they are and not stored. */
    fun openDownloaded(bytes: ByteArray) {
        _state.value = IgcImportState.Reading
        opened.trySend(Unit)
        scope.launch { _state.value = withContext(Dispatchers.Default) { analyse(bytes.toString(Charsets.ISO_8859_1)) } }
    }

    fun markImported(index: Int) = _state.update { s -> if (s is IgcImportState.Ready) s.copy(imported = s.imported + index) else s }

    fun segment(index: Int): Pair<IgcSegment, String?>? =
        (_state.value as? IgcImportState.Ready)?.let { s -> s.segments.getOrNull(index)?.let { it to s.gliderIdHeader } }

    fun clear() { _state.value = null }

    private suspend fun read(uri: Uri): IgcImportState = withContext(Dispatchers.IO) {
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readNBytesCompat(MAX_BYTES + 1)
                if (bytes.size > MAX_BYTES) null else bytes.toString(Charsets.ISO_8859_1)
            }
        }.getOrNull() ?: return@withContext IgcImportState.Failed(msg(MsgKey.IGC_UNREADABLE))
        analyse(text)
    }

    private fun analyse(text: String): IgcImportState =
        when (val r = IgcParser.parse(text)) {
            is IgcRead.Refused -> IgcImportState.Failed(r.message)
            is IgcRead.Ok -> {
                val segments = IgcFlights.segments(r.track)
                if (segments.isEmpty()) IgcImportState.Failed(msg(MsgKey.IGC_NO_FLIGHT))
                else IgcImportState.Ready(r.track.gliderId, segments)
            }
        }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (out.size() < limit) {
            val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private companion object {
        /** A day of one-second fixes is about 7 MB; anything far larger is not a flight log. */
        const val MAX_BYTES = 32 * 1024 * 1024
    }
}
