package io.github.jgfyyvzss.soarlog.data.weglide

import io.github.jgfyyvzss.soarlog.core.Msg
import io.github.jgfyyvzss.soarlog.core.MsgKey
import io.github.jgfyyvzss.soarlog.core.msg
import io.github.jgfyyvzss.soarlog.core.weglide.WeGlideAircraft
import io.github.jgfyyvzss.soarlog.core.weglide.WeGlideAircraftList
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The aircraft list in use and where it came from. [updatedAt] is null for the copy bundled with the app. */
data class AircraftListState(val aircraft: List<WeGlideAircraft>, val updatedAt: Long?)

/** The outcome of updating the aircraft list from WeGlide. */
sealed interface AircraftUpdate {
    data class Updated(val count: Int) : AircraftUpdate
    /** The list in use is kept. */
    data class Failed(val message: Msg) : AircraftUpdate
}

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

    /** Downloads the current list and saves it, or keeps the list in use and says why. */
    suspend fun update(): AircraftUpdate {
        val body = when (val r = api.fetchAircraftJson()) {
            is ApiResult.Failed -> return AircraftUpdate.Failed(r.message)
            is ApiResult.Ok -> r.value
        }
        val aircraft = WeGlideAircraftList.parse(body)
        if (aircraft.isNullOrEmpty()) return AircraftUpdate.Failed(msg(MsgKey.WEGLIDE_NO_LIST))
        val saved = withContext(Dispatchers.IO) {
            val temp = File(savedFile.parentFile, savedFile.name + ".tmp")
            temp.writeText(body)
            temp.renameTo(savedFile) || (savedFile.delete() && temp.renameTo(savedFile))
        }
        if (!saved) return AircraftUpdate.Failed(msg(MsgKey.AIRCRAFT_SAVE_FAILED))
        lock.withLock { state.value = AircraftListState(aircraft, now()) }
        return AircraftUpdate.Updated(aircraft.size)
    }
}
