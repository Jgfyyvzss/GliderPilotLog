package io.github.jgfyyvzss.gliderpilotlog.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class PilotIdentifier(val label: String, val value: String)

/** Settings backed up with the logbook. Device-specific settings come in a later step. */
data class Settings(
    val pilotName: String = "",
    val pilotIdentifiers: List<PilotIdentifier> = emptyList(),
    val recordLandingPlace: Boolean = false,
    val recordTimes: Boolean = false,
)

private val Context.settingsStore by preferencesDataStore(name = "settings")

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.settingsStore
    private val json = Json { ignoreUnknownKeys = true }
    private val identifierList = ListSerializer(PilotIdentifier.serializer())

    private object K {
        val pilotName = stringPreferencesKey("pilot_name")
        val pilotIdentifiers = stringPreferencesKey("pilot_identifiers")
        val recordLandingPlace = booleanPreferencesKey("record_landing_place")
        val recordTimes = booleanPreferencesKey("record_times")
    }

    val settings: Flow<Settings> = store.data.map { it.toSettings() }

    private fun Preferences.toSettings() = Settings(
        pilotName = this[K.pilotName] ?: "",
        pilotIdentifiers = this[K.pilotIdentifiers]
            ?.let { runCatching { json.decodeFromString(identifierList, it) }.getOrNull() }
            ?: emptyList(),
        recordLandingPlace = this[K.recordLandingPlace] ?: false,
        recordTimes = this[K.recordTimes] ?: false,
    )

    /** The profile changes only through this call, made from the Settings screen. */
    suspend fun setPilotProfile(name: String, identifiers: List<PilotIdentifier>) {
        store.edit {
            it[K.pilotName] = name.trim()
            it[K.pilotIdentifiers] = json.encodeToString(
                identifierList,
                identifiers.map { id -> PilotIdentifier(id.label.trim(), id.value.trim()) }
                    .filter { id -> id.label.isNotEmpty() || id.value.isNotEmpty() },
            )
        }
    }

    suspend fun setRecordLandingPlace(on: Boolean) {
        store.edit { it[K.recordLandingPlace] = on }
    }

    suspend fun setRecordTimes(on: Boolean) {
        store.edit { it[K.recordTimes] = on }
    }
}
