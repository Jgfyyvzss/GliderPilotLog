package io.github.jgfyyvzss.gliderpilotlog.core.backup

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

sealed interface DecodeResult {
    data class Ok(val file: BackupFile) : DecodeResult
    data class Refused(val message: String) : DecodeResult
}

/** Reads and writes the backup file with stable output, so an export, import and export round trip is identical. */
object BackupCodec {
    @OptIn(ExperimentalSerializationApi::class)
    private val writer = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
        explicitNulls = true
    }

    private val reader = Json { ignoreUnknownKeys = true }

    /**
     * Upgrades from each older format version to the next, applied in order. Version 1 is the first, so there
     * are none yet. A later version adds an entry here, for example `1 to { json -> ... }`, upgrading 1 to 2.
     */
    private val upgrades: Map<Int, (JsonObject) -> JsonObject> = emptyMap()

    /** Sorts every array by id (opening balances by role, club links by id), as the stable output requires. */
    fun sorted(file: BackupFile): BackupFile = file.copy(
        launchTypes = file.launchTypes.sortedBy { it.id },
        clubs = file.clubs.sortedBy { it.id },
        airfields = file.airfields.map { it.copy(clubIds = it.clubIds.sorted()) }.sortedBy { it.id },
        gliders = file.gliders.sortedBy { it.id },
        flights = file.flights.sortedBy { it.id },
        openingBalances = file.openingBalances.sortedBy { it.role },
        importBatches = file.importBatches.sortedBy { it.id },
    )

    fun encode(file: BackupFile): String = writer.encodeToString(BackupFile.serializer(), sorted(file)) + "\n"

    fun decode(text: String): DecodeResult {
        val root = runCatching { reader.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return DecodeResult.Refused("This is not a logbook backup file: it is not valid JSON.")
        val format = runCatching { root["format"]?.jsonPrimitive?.content }.getOrNull()
        if (format != BACKUP_FORMAT) return DecodeResult.Refused("This is not a logbook backup file.")
        val version = runCatching { root["format_version"]?.jsonPrimitive?.intOrNull }.getOrNull()
            ?: return DecodeResult.Refused("The backup file has no format version.")
        if (version > BACKUP_FORMAT_VERSION) {
            return DecodeResult.Refused(
                "This backup was made by a newer version of the app (format $version). Update the app, then try again.",
            )
        }
        if (version < 1) return DecodeResult.Refused("Unknown backup format version $version.")

        var json = root
        for (v in version until BACKUP_FORMAT_VERSION) {
            val step = upgrades[v] ?: return DecodeResult.Refused("No upgrade from backup format $v.")
            json = step(json)
        }
        return runCatching { DecodeResult.Ok(reader.decodeFromJsonElement(BackupFile.serializer(), json)) }
            .getOrElse { DecodeResult.Refused("The backup file is damaged or incomplete: ${it.message?.take(200)}") }
    }

    /** The file with its export time cleared, for comparing two exports. */
    fun withoutExportTime(text: String): String {
        val root = reader.parseToJsonElement(text).jsonObject
        return JsonObject(root + ("exported_at" to reader.parseToJsonElement("\"\""))).toString()
    }
}
