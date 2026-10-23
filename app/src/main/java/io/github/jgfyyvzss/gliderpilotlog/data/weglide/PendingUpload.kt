package io.github.jgfyyvzss.gliderpilotlog.data.weglide

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** An IGC file in memory, as a logger download hands it over before it is stored. */
class IgcFile(val name: String, val bytes: ByteArray)

/**
 * Which received file the upload screen shows. [Selection.seq] changes on every choice, so choosing the same file
 * again still counts as a new choice.
 */
class UploadSelection {
    data class Selection(val fileId: String, val seq: Long)

    private val _selected = MutableStateFlow<Selection?>(null)
    val selected: StateFlow<Selection?> = _selected
    private var seq = 0L

    fun select(fileId: String) { _selected.value = Selection(fileId, ++seq) }
    fun clear() { _selected.value = null }
}
