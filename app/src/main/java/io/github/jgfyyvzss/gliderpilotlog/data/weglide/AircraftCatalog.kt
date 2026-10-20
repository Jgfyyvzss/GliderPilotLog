package io.github.jgfyyvzss.gliderpilotlog.data.weglide

import io.github.jgfyyvzss.gliderpilotlog.core.weglide.WeGlideAircraft
import io.github.jgfyyvzss.gliderpilotlog.core.weglide.WeGlideAircraftList
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The aircraft list in use and where it came from. [updatedAt] is null for the copy bundled with the app. */
data class AircraftListState(val aircraft: List<WeGlideAircraft>, val updatedAt: Long?)

/**
 * WeGlide's aircraft list. The app ships with a copy and uses it until the pilot updates the list from Settings,
 * which replaces it with a saved copy in app-private storage. A failed update keeps the list in use.
 */
class AircraftCatalog(
    private val savedFile: File,
    private val bundled: () -> String,
    private val api: WeGlideApi,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val state = MutableStateFlow<AircraftListState?>(null)
    private val lock = Mutex()

    /** Null until [load] has run. */
    val list: StateFlow<AircraftListState?> = state

    suspend fun load(): AircraftListState = lock.withLock {
        state.value ?: withContext(Dispatchers.IO) {
            val saved = savedFile.takeIf { it.exists() }?.let { f -> WeGlideAircraftList.parse(f.readText())?.let { it to f.lastModified() } }
            val loaded = saved?.let { AircraftListState(it.first, it.second) }
                ?: AircraftListState(WeGlideAircraftList.parse(bundled()).orEmpty(), null)
            loaded.also { state.value = it }
        }
    }

    suspend fun find(id: Long): WeGlideAircraft? = load().aircraft.firstOrNull { it.id == id }

    /** Downloads the current list. Returns the number of aircraft, or a message when nothing changed. */
    suspend fun update(): Result<Int> {
        val body = when (val r = api.fetchAircraftJson()) {
            is ApiResult.Failed -> return Result.failure(IllegalStateException(r.message))
            is ApiResult.Ok -> r.value
        }
        val aircraft = WeGlideAircraftList.parse(body)
        if (aircraft.isNullOrEmpty()) return Result.failure(IllegalStateException("WeGlide sent no usable aircraft list"))
        withContext(Dispatchers.IO) {
            val temp = File(savedFile.parentFile, savedFile.name + ".tmp")
            temp.writeText(body)
            if (!temp.renameTo(savedFile)) {
                savedFile.delete()
                check(temp.renameTo(savedFile)) { "The aircraft list could not be saved" }
            }
        }
        lock.withLock { state.value = AircraftListState(aircraft, now()) }
        return Result.success(aircraft.size)
    }
}
