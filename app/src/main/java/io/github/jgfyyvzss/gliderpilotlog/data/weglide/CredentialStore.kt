package io.github.jgfyyvzss.gliderpilotlog.data.weglide

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.jgfyyvzss.gliderpilotlog.core.weglide.WeGlideCredentials
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import java.time.LocalDate
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * The WeGlide ID and date of birth, encrypted with an AES key that lives in the Android Keystore and never leaves
 * it. The file is in the no-backup folder, so neither Android's Auto Backup nor the app's own exports include it.
 * If the key or file is lost, the pilot is simply asked again.
 */
class CredentialStore(private val file: File) {
    private val _saved = MutableStateFlow<Boolean?>(null)
    /** Whether credentials are saved, for Settings; null until first read. */
    val saved: StateFlow<Boolean?> = _saved

    suspend fun load(): WeGlideCredentials? = withContext(Dispatchers.IO) {
        val result = runCatching {
            if (!file.exists()) return@runCatching null
            val stored = ByteBuffer.wrap(file.readBytes())
            val iv = ByteArray(stored.get().toInt()).also { stored.get(it) }
            val sealed = ByteArray(stored.remaining()).also { stored.get(it) }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            val (id, dob) = String(cipher.doFinal(sealed), Charsets.UTF_8).split('\n', limit = 2)
            WeGlideCredentials(id, LocalDate.parse(dob))
        }.getOrNull()
        _saved.value = result != null
        result
    }

    suspend fun save(credentials: WeGlideCredentials) = withContext(Dispatchers.IO) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // The Keystore chooses a fresh IV for every encryption; it is stored in front of the ciphertext.
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.doFinal("${credentials.userId}\n${credentials.dateOfBirth}".toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeBytes(ByteBuffer.allocate(1 + iv.size + sealed.size).put(iv.size.toByte()).put(iv).put(sealed).array())
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
        _saved.value = true
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        file.delete()
        _saved.value = false
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "weglide-credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
