package io.github.jgfyyvzss.gliderpilotlog.core.lxnav

import io.github.jgfyyvzss.gliderpilotlog.core.MsgKey
import io.github.jgfyyvzss.gliderpilotlog.core.msg
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** The values from the LXNAV section of the specification, kept in one place so they can be tuned. */
object LxnavParameters {
    /** Lines per request; the BM77 Bluetooth module has no flow control, so blocks stay small. */
    const val BLOCK_LINES = 7
    const val REPLY_TIMEOUT_MS = 4_000L
    const val ATTEMPTS = 8
    /** Failures in a row before switching to single-line requests. */
    const val SINGLE_LINE_AFTER = 2
    /** Every this many failures, reconnect instead of re-asking. */
    const val RECONNECT_EVERY = 3
    const val RECONNECT_WAIT_MS = 3_000L
    const val BACKOFF_MS = 1_000L
    const val DRAIN_QUIET_MS = 500L
    const val DRAIN_MAX_MS = 3_000L
    /** Measured on the S100: about 2.6 KB/s. */
    const val BYTES_PER_SECOND = 2_600L
}

/** One entry of the logger's logbook. The start time is UTC; the logbook's end time is always 00:00:00 and is ignored. */
data class LxnavFile(val name: String, val date: LocalDate?, val startUtc: LocalTime?, val sizeBytes: Long) {
    val start: Instant? get() = if (date != null && startUtc != null) date.atTime(startUtc).toInstant(ZoneOffset.UTC) else null
    val estimatedSeconds: Long get() = sizeBytes / LxnavParameters.BYTES_PER_SECOND
}

/** Lines saved so far out of the file's total, once the logger has said it. */
data class LxnavProgress(val lines: Int, val totalLines: Int?, val resumedFrom: Int)

/**
 * Talks to an LXNAV logger with the `PLXVC` commands observed on the S100 and described in the specification.
 * Read-only: nothing on the logger is deleted, uploaded or changed.
 */
class LxnavClient(private val connector: LxnavConnector) {
    /** Connects, resets any transfer, runs [block], and always sends FILE_CANCEL and closes when it ends. */
    suspend fun <T> session(block: suspend LxnavSession.() -> T): T {
        val link = try {
            connector.connect()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw LxnavFailure(msg(MsgKey.LXNAV_CONNECT_FAILED), e)
        }
        val session = LxnavSession(connector, link)
        try {
            session.reset()
            return session.block()
        } finally {
            withContext(NonCancellable) { session.end() }
        }
    }
}

class LxnavSession internal constructor(private val connector: LxnavConnector, private var link: LxnavLink) {
    private val pending = StringBuilder()

    internal suspend fun reset() {
        sendQuietly(CANCEL)
        drain()
    }

    internal suspend fun end() {
        sendQuietly(CANCEL)
        runCatching { link.close() }
    }

    /** The logbook, in the logger's order. */
    suspend fun logbook(): List<LxnavFile> {
        repeat(LxnavParameters.ATTEMPTS) {
            try {
                drain()
                send("PLXVC,LOGBOOKSIZE,R")
                val count = withTimeoutOrNull(LxnavParameters.REPLY_TIMEOUT_MS) {
                    while (true) {
                        val f = nextBody().split(',')
                        if (f.getOrNull(1) == "LOGBOOKSIZE" && f.getOrNull(2) == "A") return@withTimeoutOrNull f.getOrNull(3)?.toIntOrNull()
                    }
                    @Suppress("UNREACHABLE_CODE") null
                }
                if (count == 0) return emptyList()
                if (count != null) {
                    send("PLXVC,LOGBOOK,R,1,${count + 1}")
                    val rows = HashMap<Int, LxnavFile>()
                    withTimeoutOrNull(LxnavParameters.REPLY_TIMEOUT_MS) {
                        while (rows.size < count) {
                            val f = nextBody().split(',')
                            if (f.size < 10 || f[1] != "LOGBOOK" || f[2] != "A") continue
                            val index = f[3].toIntOrNull()?.takeIf { it in 1..count } ?: continue
                            rows[index] = LxnavFile(f[5], parseDate(f[6]), parseTime(f[7]), f[9].toLongOrNull() ?: continue)
                        }
                    }
                    if (rows.size == count) return (1..count).map { rows.getValue(it) }
                }
            } catch (e: IOException) {
                reconnect()
            }
        }
        throw LxnavFailure(msg(MsgKey.LXNAV_NO_LOGBOOK))
    }

    /**
     * Downloads a file by FLIGHT line ranges, saving each completed block to [partial] and resuming from it. The
     * lines are joined with CR LF after each, exactly as in the original file, and the size must match the
     * logbook's. Retries follow the specification: two failures switch to single lines, every third reconnects,
     * and the eighth gives up.
     */
    suspend fun download(file: LxnavFile, partial: PartialDownload, onProgress: (LxnavProgress) -> Unit): ByteArray {
        val lines = partial.resume(file).toMutableList()
        val resumedFrom = lines.size
        var total: Int? = null
        var failures = 0
        onProgress(LxnavProgress(lines.size, null, resumedFrom))
        while (total == null || lines.size < total) {
            // When resuming, ask again for the last saved line too: it gives the total, and checks the partial.
            val first = if (total == null && lines.isNotEmpty()) lines.size else lines.size + 1
            val width = if (failures < LxnavParameters.SINGLE_LINE_AFTER) LxnavParameters.BLOCK_LINES else 1
            val end = lines.size + 1 + width
            var dropped = false
            val block = try {
                block(file.name, first, end)
            } catch (e: IOException) {
                dropped = true
                null
            }
            if (block != null) {
                total = block.total
                if (first == lines.size && block.lines[first] != lines.last()) {
                    // The saved lines do not belong to this file after all; start again.
                    partial.delete()
                    lines.clear()
                    partial.resume(file)
                    total = null
                    continue
                }
                val fresh = (lines.size + 1 until min(end, block.total + 1)).map { block.lines.getValue(it) }
                partial.append(fresh)
                lines += fresh
                if (failures > 0) failures--
                onProgress(LxnavProgress(lines.size, total, resumedFrom))
                continue
            }
            failures++
            if (failures >= LxnavParameters.ATTEMPTS) throw LxnavFailure(msg(MsgKey.LXNAV_GAVE_UP, lines.size + 1))
            if (dropped || failures % LxnavParameters.RECONNECT_EVERY == 0) {
                reconnect()
            } else {
                sendQuietly(CANCEL)
                delay(LxnavParameters.BACKOFF_MS * failures)
            }
            try {
                drain()
            } catch (e: IOException) {
                // Still down; the next request finds out.
            }
        }
        val bytes = lines.joinToString("") { it + "\r\n" }.toByteArray(Charsets.ISO_8859_1)
        partial.delete()
        if (bytes.size.toLong() != file.sizeBytes) throw LxnavFailure(msg(MsgKey.LXNAV_SIZE_MISMATCH, bytes.size, file.sizeBytes))
        return bytes
    }

    private class Block(val total: Int, val lines: Map<Int, String>)

    /** Lines [first, end), or null if they did not all arrive in time. */
    private suspend fun block(name: String, first: Int, end: Int): Block? {
        send("PLXVC,FLIGHT,R,$name,$first,$end")
        val got = HashMap<Int, String>()
        return withTimeoutOrNull(LxnavParameters.REPLY_TIMEOUT_MS) {
            while (true) {
                // PLXVC,FLIGHT,A,<empty>,<line>,<total>,<content>; the content may itself contain commas.
                val f = nextBody().split(',', limit = 7)
                if (f.size < 7 || f[1] != "FLIGHT" || f[2] != "A") continue
                val number = f[4].toIntOrNull() ?: continue
                val total = f[5].toIntOrNull() ?: continue
                if (number !in first until end) continue // a stale reply to an earlier request
                got[number] = f[6]
                if ((first until min(end, total + 1)).all { it in got }) return@withTimeoutOrNull Block(total, got)
            }
            @Suppress("UNREACHABLE_CODE") null
        }
    }

    /** The body of the next valid `PLXV` sentence; the NMEA stream and anything with a bad checksum are skipped. */
    private suspend fun nextBody(): String {
        while (true) {
            val newline = pending.indexOf("\n")
            if (newline < 0) {
                pending.append(String(link.receive(), Charsets.ISO_8859_1))
                continue
            }
            val line = pending.substring(0, newline)
            pending.delete(0, newline + 1)
            val body = Nmea.body(line) ?: continue
            if (body.startsWith("PLXV")) return body
        }
    }

    /** Discards input until the link has been quiet for a moment, so old replies are not mistaken for new ones. */
    private suspend fun drain() {
        withTimeoutOrNull(LxnavParameters.DRAIN_MAX_MS) {
            while (withTimeoutOrNull(LxnavParameters.DRAIN_QUIET_MS) { link.receive() } != null) Unit
        }
        pending.clear()
    }

    private suspend fun reconnect() {
        runCatching { link.close() }
        delay(LxnavParameters.RECONNECT_WAIT_MS)
        try {
            link = connector.connect()
            sendQuietly(CANCEL)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Still unreachable: the closed link fails the next request, which counts as another failure.
        }
        pending.clear()
    }

    private suspend fun send(body: String) {
        require(' ' !in body) { "commands have no spaces" }
        link.send(Nmea.sentence(body))
    }

    private suspend fun sendQuietly(body: String) {
        try {
            send(body)
        } catch (e: IOException) {
            // A dropped link is handled by the next read.
        }
    }

    private companion object {
        const val CANCEL = "PLXVC,FILE_CANCEL,R"
        val dateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

        fun parseDate(text: String): LocalDate? = runCatching { LocalDate.parse(text, dateFormat) }.getOrNull()
        fun parseTime(text: String): LocalTime? = runCatching { LocalTime.parse(text) }.getOrNull()
    }
}
