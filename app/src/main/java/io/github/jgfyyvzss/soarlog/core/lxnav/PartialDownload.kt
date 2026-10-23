package io.github.jgfyyvzss.soarlog.core.lxnav

import java.io.File

/**
 * The lines of an unfinished download, saved block by block so a retry resumes where it stopped. Only one partial
 * is kept: starting another file replaces it. It is deleted when the download finishes or is cancelled, and when it
 * is older than [MAX_AGE_MS].
 */
interface PartialDownload {
    /** The saved lines of this file, or none if the partial is for another file or has expired. */
    fun resume(file: LxnavFile): List<String>
    fun append(lines: List<String>)
    fun delete()
}

/**
 * A plain text file: a header naming the file and its size, then one line per IGC line, each ended by LF. IGC lines
 * never contain LF, so a line cut short by an interruption is recognisable and dropped.
 */
class FilePartialDownload(private val file: File, private val now: () -> Long = System::currentTimeMillis) : PartialDownload {
    override fun resume(file: LxnavFile): List<String> {
        if (!this.file.exists()) return start(file)
        if (now() - this.file.lastModified() > MAX_AGE_MS) return start(file)
        val text = runCatching { this.file.readText(Charsets.ISO_8859_1) }.getOrNull() ?: return start(file)
        val parts = text.split('\n')
        if (parts.first() != header(file)) return start(file)
        // The last part is empty after a complete line, or a line cut short; drop it either way, and rewrite the
        // file so new lines follow the last complete one.
        val lines = parts.drop(1).dropLast(1)
        if (parts.last().isNotEmpty()) this.file.writeText(header(file) + "\n" + lines.joinToString("") { it + "\n" }, Charsets.ISO_8859_1)
        return lines
    }

    override fun append(lines: List<String>) {
        if (lines.isEmpty()) return
        this.file.appendText(lines.joinToString("") { it + "\n" }, Charsets.ISO_8859_1)
    }

    override fun delete() {
        this.file.delete()
    }

    /** Deletes an expired partial, for example at app start. */
    fun deleteIfExpired() {
        if (this.file.exists() && now() - this.file.lastModified() > MAX_AGE_MS) this.file.delete()
    }

    private fun start(file: LxnavFile): List<String> {
        this.file.parentFile?.mkdirs()
        this.file.writeText(header(file) + "\n", Charsets.ISO_8859_1)
        return emptyList()
    }

    private fun header(file: LxnavFile) = "lxnav-partial,${file.name},${file.sizeBytes}"

    companion object {
        /** Two days, as the pilot chose. */
        const val MAX_AGE_MS = 2L * 24 * 60 * 60 * 1000
    }
}
