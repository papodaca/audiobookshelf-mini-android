package io.github.miniaudiobookshelf.android.store

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.miniaudiobookshelf.core.CredentialMode
import io.github.miniaudiobookshelf.core.SecretStoreException
import io.github.miniaudiobookshelf.core.StoredSecret
import io.github.miniaudiobookshelf.core.TokenStore
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class KeystoreSecretStore private constructor(
    private val prefs: SharedPreferences,
    private val key: SecretKey,
) : TokenStore {
    override suspend fun save(origin: String, secret: String, mode: CredentialMode, access: String?) {
        writeSecret(
            prefs,
            origin,
            encrypt(secret),
            mode,
            access?.takeIf { it.isNotEmpty() }?.let { encrypt(it) },
        )
    }

    override suspend fun clear(origin: String) {
        clearSecret(prefs, origin)
    }

    override suspend fun load(origin: String): StoredSecret? {
        val stored = readSecret(prefs, origin) ?: return null
        return try {
            StoredSecret(
                secret = decrypt(stored.secret),
                mode = stored.mode,
                access = stored.access?.let { decrypt(it) },
            )
        } catch (_: Exception) {
            null
        }
    }

    internal fun importPlain(origin: String, stored: StoredSecret) {
        if (readSecret(prefs, origin) != null) return
        writeSecret(
            prefs,
            origin,
            encrypt(stored.secret),
            stored.mode,
            stored.access?.takeIf { it.isNotEmpty() }?.let { encrypt(it) },
        )
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)
        return Base64.getEncoder().encodeToString(combined)
    }

    private fun decrypt(blob: String): String {
        val combined = Base64.getDecoder().decode(blob)
        if (combined.size <= IV_SIZE) {
            throw SecretStoreException("encrypted value too short")
        }
        val iv = combined.copyOfRange(0, IV_SIZE)
        val ciphertext = combined.copyOfRange(IV_SIZE, combined.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    companion object {
        private const val FILE = "mabs_secret_store_ks"
        private const val KEY_ALIAS = "mabs_secret_aes"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
        private const val TAG_BITS = 128

        fun create(context: Context): KeystoreSecretStore? {
            return try {
                val key = getOrCreateKey()
                val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                KeystoreSecretStore(prefs, key)
            } catch (_: Exception) {
                null
            }
        }

        private fun getOrCreateKey(): SecretKey {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            if (existing != null) {
                return existing.secretKey
            }
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            generator.init(spec)
            return generator.generateKey()
        }
    }
}
