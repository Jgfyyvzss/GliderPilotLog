package io.github.jgfyyvzss.soarlog.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Small helpers for files the pilot picks through the system document pickers. */
object Documents {
    suspend fun readText(resolver: ContentResolver, uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching { resolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull()
    }

    suspend fun displayName(resolver: ContentResolver, uri: Uri): String = withContext(Dispatchers.IO) {
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment ?: "file"
    }

    /** Creates a new file in a folder picked with the tree picker and writes [text] as UTF-8. */
    suspend fun writeToFolder(resolver: ContentResolver, tree: Uri, name: String, mimeType: String, text: String) = withContext(Dispatchers.IO) {
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val doc = DocumentsContract.createDocument(resolver, parent, mimeType, name) ?: error("The folder would not accept $name")
        resolver.openOutputStream(doc, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: error("$name could not be written")
    }
}
