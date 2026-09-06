package io.github.miniaudiobookshelf.android.store

import android.content.Context
import android.content.SharedPreferences
import io.github.miniaudiobookshelf.core.CredentialMode
import io.github.miniaudiobookshelf.core.FailingTokenStore
import io.github.miniaudiobookshelf.core.SecretStoreException
import io.github.miniaudiobookshelf.core.StoredSecret
import io.github.miniaudiobookshelf.core.TokenStore

object EncryptedSecretStore {
    fun create(context: Context): TokenStore {
        val app = context.applicationContext
        val store = KeystoreSecretStore.create(app) ?: return FailingTokenStore()
        migrateLegacyEncryptedPrefs(app, store)
        return store
    }
}

internal const val KIND_REFRESH = "refresh"
internal const val KIND_API_KEY = "apikey"

internal fun secretKey(origin: String) = "$origin/secret"
internal fun modeKey(origin: String) = "$origin/mode"
internal fun accessKey(origin: String) = "$origin/access"

internal fun writeSecret(
    prefs: SharedPreferences,
    origin: String,
    secret: String,
    mode: CredentialMode,
    access: String?,
) {
    val kind = if (mode == CredentialMode.API_KEY) KIND_API_KEY else KIND_REFRESH
    val editor = prefs.edit()
        .putString(secretKey(origin), secret)
        .putString(modeKey(origin), kind)
    if (access.isNullOrEmpty()) {
        editor.remove(accessKey(origin))
    } else {
        editor.putString(accessKey(origin), access)
    }
    if (!editor.commit()) {
        throw SecretStoreException("encrypted store write failed")
    }
}

internal fun clearSecret(prefs: SharedPreferences, origin: String) {
    prefs.edit()
        .remove(secretKey(origin))
        .remove(modeKey(origin))
        .remove(accessKey(origin))
        .commit()
}

internal fun readSecret(prefs: SharedPreferences, origin: String): StoredSecret? {
    val secret = prefs.getString(secretKey(origin), null) ?: return null
    if (secret.isBlank()) return null
    val kind = prefs.getString(modeKey(origin), null) ?: return null
    val mode = if (kind == KIND_API_KEY) CredentialMode.API_KEY else CredentialMode.PASSWORD
    return StoredSecret(secret, mode, prefs.getString(accessKey(origin), null))
}
