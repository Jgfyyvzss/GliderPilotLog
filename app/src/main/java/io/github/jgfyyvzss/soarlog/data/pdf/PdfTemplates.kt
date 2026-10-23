package io.github.jgfyyvzss.soarlog.data.pdf

import android.content.Context
import android.net.Uri
import io.github.jgfyyvzss.soarlog.core.MsgKey
import io.github.jgfyyvzss.soarlog.core.msg
import io.github.jgfyyvzss.soarlog.core.pdf.LogbookTemplate
import io.github.jgfyyvzss.soarlog.core.pdf.TemplateRead
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A template the export can use: one of the built-ins, or one the pilot imported. */
data class TemplateEntry(val id: String, val builtIn: Boolean, val template: LogbookTemplate)

/**
 * The logbook PDF templates: the built-ins from the app's assets, and any the pilot has imported, kept as files in
 * app-private storage. There is no in-app editor.
 */
class PdfTemplates(private val context: Context) {
    private val dir get() = File(context.filesDir, "pdf-templates")

    suspend fun all(): List<TemplateEntry> = withContext(Dispatchers.IO) {
        val builtIn = BUILT_IN.mapNotNull { id ->
            val text = runCatching { context.assets.open("pdf-templates/$id.json").use { it.readBytes().decodeToString() } }.getOrNull()
            (text?.let(LogbookTemplate::parse) as? TemplateRead.Ok)?.let { TemplateEntry(id, true, it.template) }
        }
        val imported = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNull { f ->
            (LogbookTemplate.parse(f.readText()) as? TemplateRead.Ok)?.let { TemplateEntry("file:" + f.nameWithoutExtension, false, it.template) }
        }.sortedBy { it.template.name.lowercase() }
        builtIn + imported
    }

    /** Checks and stores a template file; one with the same name replaces the earlier import. */
    suspend fun import(uri: Uri): TemplateRead = withContext(Dispatchers.IO) {
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readBytes()
                if (bytes.size > MAX_BYTES) null else bytes.decodeToString()
            }
        }.getOrNull() ?: return@withContext TemplateRead.Refused(msg(MsgKey.PDF_TEMPLATE_UNREADABLE))
        val read = LogbookTemplate.parse(text)
        if (read is TemplateRead.Ok) {
            dir.mkdirs()
            File(dir, fileName(read.template.name)).writeText(text)
        }
        read
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        if (id.startsWith("file:")) File(dir, id.removePrefix("file:") + ".json").delete()
    }

    private fun fileName(name: String) = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "template" } + ".json"

    companion object {
        /** The built-in templates' asset names, in the order they are offered. */
        val BUILT_IN = listOf("simple", "compact", "gfa")
        private const val MAX_BYTES = 256 * 1024
    }
}
