package io.github.jgfyyvzss.gliderpilotlog.core.igc

import java.io.File
import java.security.MessageDigest
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * An IGC file the app has received, with its two jobs: logging its flights in the logbook and uploading it to
 * WeGlide. Either, both or neither may be wanted, so each is shown wherever the file is.
 */
@Serializable
data class StoredIgc(
    /** From the file's content, so receiving the same file again finds this entry and its statuses. */
    val id: String,
    val name: String,
    val receivedAt: Long,
    val sizeBytes: Long,
    /** The flying segments found in it; 0 when the file has none, so there is nothing to log. */
    val flights: Int,
    /** The first segment's launch, as epoch milliseconds, and its duration, for showing the file. */
    val firstLaunchAt: Long? = null,
    val firstDurationMinutes: Int? = null,
    val gliderIdHeader: String? = null,
    /** The segments saved as logbook flights. */
    val logged: Set<Int> = emptySet(),
    /** The glider of the last flight saved from this file, which supplies the WeGlide aircraft. */
    val loggedGliderId: String? = null,
    val uploaded: Boolean = false,
    val weglideFlightId: Long? = null,
) {
    val fullyLogged: Boolean get() = flights > 0 && logged.size >= flights
    val firstLaunch: Instant? get() = firstLaunchAt?.let(Instant::ofEpochMilli)
}

/**
 * Received IGC files, kept byte for byte in a private folder so a long logger download survives a crash, a flat
 * battery or closing the app, and a file can be uploaded later on Wi-Fi. The logbook is not an archive of IGC
 * files: each is deleted when it is [MAX_AGE_MS] old, when more than [MAX_FILES] are kept (oldest first), or when
 * the pilot removes it.
 */
class IgcLibrary(private val dir: File, private val now: () -> Long = System::currentTimeMillis) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Stores a file, or finds the copy already stored, and returns its entry. */
    @Synchronized
    fun add(name: String, bytes: ByteArray): StoredIgc {
        dir.mkdirs()
        val id = idOf(bytes)
        get(id)?.let { return it }
        val segments = when (val r = IgcParser.parse(bytes.toString(Charsets.ISO_8859_1))) {
            is IgcRead.Ok -> r.track to IgcFlights.segments(r.track)
            is IgcRead.Refused -> null
        }
        val first = segments?.second?.firstOrNull()
        val entry = StoredIgc(
            id = id,
            name = name.ifBlank { "flight.igc" },
            receivedAt = now(),
            sizeBytes = bytes.size.toLong(),
            flights = segments?.second?.size ?: 0,
            firstLaunchAt = first?.launch?.time?.toEpochMilli(),
            firstDurationMinutes = first?.durationMinutes,
            gliderIdHeader = segments?.first?.gliderId,
        )
        write(File(dir, "$id.igc"), bytes)
        write(meta(id), json.encodeToString(StoredIgc.serializer(), entry).toByteArray(Charsets.UTF_8))
        prune()
        return entry
    }

    @Synchronized
    fun get(id: String): StoredIgc? {
        val f = meta(id)
        if (!f.exists() || !File(dir, "$id.igc").exists()) return null
        return runCatching { json.decodeFromString(StoredIgc.serializer(), f.readText(Charsets.UTF_8)) }.getOrNull()
    }

    /** The file's bytes exactly as received. */
    @Synchronized
    fun bytes(id: String): ByteArray? = File(dir, "$id.igc").takeIf { it.exists() }?.readBytes()

    /** Newest first, after removing expired files. */
    @Synchronized
    fun list(): List<StoredIgc> {
        prune()
        return entries().sortedByDescending { it.receivedAt }
    }

    @Synchronized
    fun update(id: String, change: (StoredIgc) -> StoredIgc): StoredIgc? {
        val updated = change(get(id) ?: return null)
        write(meta(id), json.encodeToString(StoredIgc.serializer(), updated).toByteArray(Charsets.UTF_8))
        return updated
    }

    @Synchronized
    fun remove(id: String) {
        File(dir, "$id.igc").delete()
        meta(id).delete()
    }

    /** Deletes files past their age, then the oldest beyond the count, and any half-written leftovers. */
    @Synchronized
    fun prune() {
        val all = entries()
        val cutoff = now() - MAX_AGE_MS
        val expired = all.filter { it.receivedAt < cutoff }
        val extra = (all - expired.toSet()).sortedByDescending { it.receivedAt }.drop(MAX_FILES)
        (expired + extra).forEach { remove(it.id) }
        val known = entries().map { it.id }.toSet()
        dir.listFiles()?.filter { it.nameWithoutExtension !in known }?.forEach { it.delete() }
    }

    private fun entries(): List<StoredIgc> =
        dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNull { get(it.nameWithoutExtension) }

    private fun meta(id: String) = File(dir, "$id.json")

    /** Writes through a temporary file, so a crash never leaves a half-written file under the real name. */
    private fun write(file: File, bytes: ByteArray) {
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }

    companion object {
        /** Seven days, as the pilot chose: enough to download at the field and upload at home after a weekend away. */
        const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
        /** A busy instructor's week, while keeping storage bounded. */
        const val MAX_FILES = 50

        fun idOf(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).take(12).joinToString("") { "%02x".format(it) }
    }
}
