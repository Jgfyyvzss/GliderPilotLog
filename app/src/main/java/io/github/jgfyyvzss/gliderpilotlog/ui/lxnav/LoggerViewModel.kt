package io.github.jgfyyvzss.gliderpilotlog.ui.lxnav

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jgfyyvzss.gliderpilotlog.core.Msg
import io.github.jgfyyvzss.gliderpilotlog.core.MsgKey
import io.github.jgfyyvzss.gliderpilotlog.core.lxnav.LxnavClient
import io.github.jgfyyvzss.gliderpilotlog.core.lxnav.LxnavFailure
import io.github.jgfyyvzss.gliderpilotlog.core.lxnav.LxnavFile
import io.github.jgfyyvzss.gliderpilotlog.core.lxnav.LxnavProgress
import io.github.jgfyyvzss.gliderpilotlog.core.lxnav.PartialDownload
import io.github.jgfyyvzss.gliderpilotlog.core.msg
import io.github.jgfyyvzss.gliderpilotlog.data.weglide.IgcFile
import io.github.jgfyyvzss.gliderpilotlog.data.lxnav.BluetoothLoggers
import io.github.jgfyyvzss.gliderpilotlog.data.lxnav.BluetoothStatus
import io.github.jgfyyvzss.gliderpilotlog.data.lxnav.PairedDevice
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface LoggerState {
    data class Devices(val status: BluetoothStatus) : LoggerState
    data class Connecting(val device: PairedDevice) : LoggerState
    /** The logger's files, newest first. */
    data class Files(val device: PairedDevice, val files: List<LxnavFile>) : LoggerState
    data class Downloading(val device: PairedDevice, val file: LxnavFile, val progress: LxnavProgress, val startedAt: Long) : LoggerState
    data class Failed(val device: PairedDevice?, val message: Msg, val retry: () -> Unit) : LoggerState
}

/**
 * From logger: paired devices, the chosen logger's files, and a download that hands the file to the IGC import.
 * Each step opens its own session, so a dropped link between steps costs nothing.
 */
class LoggerViewModel(
    private val loggers: BluetoothLoggers,
    private val partial: PartialDownload,
    /** Where a finished download goes: the logbook import, or the WeGlide upload screen that opened this one. */
    private val onDownloaded: suspend (IgcFile) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val _state = MutableStateFlow<LoggerState>(LoggerState.Devices(loggers.status()))
    val state: StateFlow<LoggerState> = _state
    private var job: Job? = null
    private var files: List<LxnavFile> = emptyList()

    /** Re-reads Bluetooth, for example after the pilot returns from the system settings. */
    fun refresh() {
        if (_state.value is LoggerState.Devices) _state.value = LoggerState.Devices(loggers.status())
    }

    fun backToDevices() {
        job?.cancel()
        _state.value = LoggerState.Devices(loggers.status())
    }

    fun open(device: PairedDevice) {
        job?.cancel()
        _state.value = LoggerState.Connecting(device)
        job = viewModelScope.launch {
            attempt(device, retry = { open(device) }) {
                val book = LxnavClient(loggers.connector(device.address)).session { logbook() }
                files = book.sortedByDescending { it.start }
                _state.value = LoggerState.Files(device, files)
            }
        }
    }

    fun download(device: PairedDevice, file: LxnavFile) {
        job?.cancel()
        val started = now()
        _state.value = LoggerState.Downloading(device, file, LxnavProgress(0, null, 0), started)
        job = viewModelScope.launch {
            attempt(device, retry = { download(device, file) }) {
                val bytes = LxnavClient(loggers.connector(device.address)).session {
                    download(file, partial) { p -> _state.value = LoggerState.Downloading(device, file, p, started) }
                }
                _state.value = LoggerState.Files(device, files)
                onDownloaded(IgcFile(file.name, bytes))
            }
        }
    }

    /** Stops the download and discards what was saved; FILE_CANCEL is sent as the session closes. */
    fun cancelDownload() {
        val s = _state.value as? LoggerState.Downloading ?: return
        job?.cancel()
        partial.delete()
        _state.value = LoggerState.Files(s.device, files)
    }

    private suspend fun attempt(device: PairedDevice, retry: () -> Unit, block: suspend () -> Unit) {
        try {
            withContext(Dispatchers.Default) { block() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LxnavFailure) {
            _state.value = LoggerState.Failed(device, e.shown, retry)
        } catch (e: Exception) {
            _state.value = LoggerState.Failed(device, msg(MsgKey.LXNAV_CONNECT_FAILED), retry)
        }
    }
}
