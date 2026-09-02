package io.github.miniaudiobookshelf.android.store

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import io.github.miniaudiobookshelf.core.CredentialMode
import io.github.miniaudiobookshelf.core.FailingTokenStore
import io.github.miniaudiobookshelf.core.SecretStoreException
import io.github.miniaudiobookshelf.core.TokenStore

@Suppress("DEPRECATION")
class EncryptedSecretStore private constructor(
    private val prefs: SharedPreferences,
) : TokenStore {
    override suspend fun save(origin: String, secret: String, mode: CredentialMode) {
        val kind = if (mode == CredentialMode.API_KEY) KIND_API_KEY else KIND_REFRESH
        val ok = prefs.edit()
            .putString(secretKey(origin), secret)
            .putString(modeKey(origin), kind)
            .commit()
        if (!ok) {
            throw SecretStoreException("encrypted store write failed")
        }
    }

    override suspend fun clear(origin: String) {
        prefs.edit()
            .remove(secretKey(origin))
            .remove(modeKey(origin))
            .commit()
    }

    override suspend fun load(origin: String): Pair<String, CredentialMode>? {
        val secret = prefs.getString(secretKey(origin), null) ?: return null
        val kind = prefs.getString(modeKey(origin), null) ?: return null
        val mode = if (kind == KIND_API_KEY) CredentialMode.API_KEY else CredentialMode.PASSWORD
        return secret to mode
    }

    companion object {
        private const val FILE = "mabs_secret_store"
        private const val KIND_REFRESH = "refresh"
        private const val KIND_API_KEY = "apikey"

        fun create(context: Context): TokenStore {
            return try {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                val prefs = EncryptedSharedPreferences.create(
                    context,
                    FILE,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
                EncryptedSecretStore(prefs)
            } catch (_: Exception) {
                FailingTokenStore()
            }
        }

        private fun secretKey(origin: String) = "$origin/secret"
        private fun modeKey(origin: String) = "$origin/mode"
    }
}
