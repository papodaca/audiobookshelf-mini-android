@file:Suppress("DEPRECATION")

package io.github.miniaudiobookshelf.android.store

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File

private const val LEGACY_FILE = "mabs_secret_store"

internal fun migrateLegacyEncryptedPrefs(context: Context, dest: KeystoreSecretStore) {
    val prefsFile = File(context.applicationInfo.dataDir, "shared_prefs/$LEGACY_FILE.xml")
    if (!prefsFile.isFile) return
    val prefs = openLegacyEncrypted(context) ?: return
    try {
        val origins = prefs.all.keys.mapNotNull { key ->
            key.removeSuffix("/secret").takeIf { it != key && key.endsWith("/secret") }
        }.distinct()
        for (origin in origins) {
            val stored = readSecret(prefs, origin) ?: continue
            dest.importPlain(origin, stored)
        }
        context.deleteSharedPreferences(LEGACY_FILE)
    } catch (_: Exception) {
        // Leave the legacy file in place so the next launch can retry.
    }
}

private fun openLegacyEncrypted(context: Context): SharedPreferences? {
    return try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            LEGACY_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (_: Exception) {
        null
    }
}
