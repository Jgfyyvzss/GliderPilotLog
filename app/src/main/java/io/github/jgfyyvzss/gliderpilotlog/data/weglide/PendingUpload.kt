package io.github.jgfyyvzss.gliderpilotlog.data.weglide

import android.content.ContentResolver
import android.net.Uri
import io.github.jgfyyvzss.gliderpilotlog.data.Documents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** An IGC file held in memory, byte for byte, for one upload. It is never written to storage. */
class IgcFile(val name: String, val bytes: ByteArray)

/**
 * The file waiting to be uploaded, from the file picker or a logger download. It is dropped after a successful
 * upload and when the pilot leaves the upload screen.
 */
class PendingUpload {
    private val _file = MutableStateFlow<IgcFile?>(null)
    val file: StateFlow<IgcFile?> = _file

    fun set(file: IgcFile) { _file.value = file }
    fun clear() { _file.value = null }

    companion object {
        /** A day of one-second fixes is about 7 MB; anything far larger is not a flight log. */
        private const val MAX_BYTES = 32 * 1024 * 1024

        suspend fun read(resolver: ContentResolver, uri: Uri): IgcFile? = withContext(Dispatchers.IO) {
            val bytes = runCatching {
                resolver.openInputStream(uri)?.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(64 * 1024)
                    while (out.size() <= MAX_BYTES) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                    }
                    out.toByteArray().takeIf { it.size <= MAX_BYTES }
                }
            }.getOrNull() ?: return@withContext null
            IgcFile(Documents.displayName(resolver, uri), bytes)
        }
    }
}
