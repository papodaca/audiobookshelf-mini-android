package io.github.miniaudiobookshelf.android.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.miniaudiobookshelf.core.AuthMode
import io.github.miniaudiobookshelf.core.SettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

private val Context.mabsSettings: DataStore<Preferences> by preferencesDataStore(name = "mabs_settings")

class DataStoreSettingsStore(context: Context) : SettingsStore {
    private val dataStore = context.applicationContext.mabsSettings
    private val lock = Any()
    private var serverUrl: String = ""
    private var deviceId: String = ""
    private var pins: Map<String, String> = emptyMap()
    private var httpAck: Set<String> = emptySet()
    private var libraryId: String = ""
    private var playbackRate: Double = 1.0
    private var lastItemId: String = ""
    private var lastEpisodeId: String = ""
    private var lastTitle: String = ""
    private var lastAuthor: String = ""
    private var allowMeteredDownloads: Boolean = false
    private var queueEntries: String = ""
    private val lastUsernames = mutableMapOf<String, String>()
    private val lastAuthModes = mutableMapOf<String, AuthMode>()

    init {
        val prefs = runBlocking { dataStore.data.first() }
        serverUrl = prefs[SERVER_URL].orEmpty()
        deviceId = prefs[DEVICE_ID].orEmpty()
        httpAck = prefs[HTTP_ACK].orEmpty()
        pins = decodePins(prefs[PINS].orEmpty())
        libraryId = prefs[LIBRARY_ID].orEmpty()
        playbackRate = prefs[PLAYBACK_RATE]?.toDoubleOrNull()?.coerceIn(0.5, 3.0) ?: 1.0
        lastItemId = prefs[LAST_ITEM_ID].orEmpty()
        lastEpisodeId = prefs[LAST_EPISODE_ID].orEmpty()
        lastTitle = prefs[LAST_TITLE].orEmpty()
        lastAuthor = prefs[LAST_AUTHOR].orEmpty()
        allowMeteredDownloads = prefs[ALLOW_METERED] ?: false
        queueEntries = prefs[QUEUE_ENTRIES].orEmpty()
        for ((key, value) in prefs.asMap()) {
            val name = key.name
            val text = value as? String ?: continue
            when {
                name.startsWith(LAST_USERNAME_PREFIX) -> {
                    lastUsernames[name.removePrefix(LAST_USERNAME_PREFIX)] = text
                }
                name.startsWith(LAST_AUTH_MODE_PREFIX) -> {
                    parseAuthMode(text)?.let { lastAuthModes[name.removePrefix(LAST_AUTH_MODE_PREFIX)] = it }
                }
            }
        }
    }

    override fun getServerUrl(): String = synchronized(lock) { serverUrl }

    override fun setServerUrl(value: String) {
        synchronized(lock) { serverUrl = value }
        persist { it[SERVER_URL] = value }
    }

    override fun getDeviceId(): String = synchronized(lock) { deviceId }

    override fun setDeviceId(value: String) {
        synchronized(lock) { deviceId = value }
        persist { it[DEVICE_ID] = value }
    }

    override fun getPinnedCertificates(): Map<String, String> = synchronized(lock) { pins }

    override fun setPinnedCertificate(origin: String, pem: String) {
        val next: Map<String, String>
        synchronized(lock) {
            pins = pins + (origin to pem)
            next = pins
        }
        persist { it[PINS] = encodePins(next) }
    }

    override fun isHttpAcknowledged(origin: String): Boolean = synchronized(lock) { origin in httpAck }

    override fun setHttpAcknowledged(origin: String, acknowledged: Boolean) {
        val next: Set<String>
        synchronized(lock) {
            httpAck = if (acknowledged) httpAck + origin else httpAck - origin
            next = httpAck
        }
        persist { it[HTTP_ACK] = next }
    }

    override fun getLibraryId(): String = synchronized(lock) { libraryId }

    override fun setLibraryId(value: String) {
        synchronized(lock) { libraryId = value }
        persist { it[LIBRARY_ID] = value }
    }

    override fun getPlaybackRate(): Double = synchronized(lock) { playbackRate }

    override fun setPlaybackRate(value: Double) {
        val rate = value.coerceIn(0.5, 3.0)
        synchronized(lock) { playbackRate = rate }
        persist { it[PLAYBACK_RATE] = rate.toString() }
    }

    override fun getLastItemId(): String = synchronized(lock) { lastItemId }

    override fun setLastItemId(value: String) {
        synchronized(lock) { lastItemId = value }
        persist { it[LAST_ITEM_ID] = value }
    }

    override fun getLastEpisodeId(): String = synchronized(lock) { lastEpisodeId }

    override fun setLastEpisodeId(value: String) {
        synchronized(lock) { lastEpisodeId = value }
        persist { it[LAST_EPISODE_ID] = value }
    }

    override fun getLastTitle(): String = synchronized(lock) { lastTitle }

    override fun setLastTitle(value: String) {
        synchronized(lock) { lastTitle = value }
        persist { it[LAST_TITLE] = value }
    }

    override fun getLastAuthor(): String = synchronized(lock) { lastAuthor }

    override fun setLastAuthor(value: String) {
        synchronized(lock) { lastAuthor = value }
        persist { it[LAST_AUTHOR] = value }
    }

    override fun getAllowMeteredDownloads(): Boolean = synchronized(lock) { allowMeteredDownloads }

    override fun setAllowMeteredDownloads(value: Boolean) {
        synchronized(lock) { allowMeteredDownloads = value }
        persist { it[ALLOW_METERED] = value }
    }

    override fun getQueueEntries(): String = synchronized(lock) { queueEntries }

    override fun setQueueEntries(value: String) {
        synchronized(lock) { queueEntries = value }
        persist { it[QUEUE_ENTRIES] = value }
    }

    override fun getLastUsername(origin: String): String = synchronized(lock) {
        lastUsernames[origin].orEmpty()
    }

    override fun setLastUsername(origin: String, value: String) {
        synchronized(lock) { lastUsernames[origin] = value }
        persist { it[stringPreferencesKey(LAST_USERNAME_PREFIX + origin)] = value }
    }

    override fun getLastAuthMode(origin: String): AuthMode? = synchronized(lock) {
        lastAuthModes[origin]
    }

    override fun setLastAuthMode(origin: String, mode: AuthMode) {
        synchronized(lock) { lastAuthModes[origin] = mode }
        persist { it[stringPreferencesKey(LAST_AUTH_MODE_PREFIX + origin)] = mode.name.lowercase() }
    }

    private fun persist(mutate: (MutablePreferences) -> Unit) {
        runBlocking {
            dataStore.edit(mutate)
        }
    }

    companion object {
        private val SERVER_URL = stringPreferencesKey("server_url")
        private val DEVICE_ID = stringPreferencesKey("device_id")
        private val HTTP_ACK = stringSetPreferencesKey("http_ack_origins")
        private val PINS = stringPreferencesKey("pinned_certificates")
        private val LIBRARY_ID = stringPreferencesKey("library_id")
        private val PLAYBACK_RATE = stringPreferencesKey("playback_rate")
        private val LAST_ITEM_ID = stringPreferencesKey("last_item_id")
        private val LAST_EPISODE_ID = stringPreferencesKey("last_episode_id")
        private val LAST_TITLE = stringPreferencesKey("last_title")
        private val LAST_AUTHOR = stringPreferencesKey("last_author")
        private val ALLOW_METERED = booleanPreferencesKey("allow_metered_downloads")
        private val QUEUE_ENTRIES = stringPreferencesKey("queue_entries")
        private const val LAST_USERNAME_PREFIX = "last_username."
        private const val LAST_AUTH_MODE_PREFIX = "last_auth_mode."

        private fun parseAuthMode(raw: String): AuthMode? {
            return when (raw.lowercase()) {
                "password" -> AuthMode.PASSWORD
                "api_key" -> AuthMode.API_KEY
                else -> AuthMode.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
            }
        }

        private fun encodePins(pins: Map<String, String>): String {
            val obj = JSONObject()
            pins.forEach { (origin, pem) -> obj.put(origin, pem) }
            return obj.toString()
        }

        private fun decodePins(raw: String): Map<String, String> {
            if (raw.isEmpty()) return emptyMap()
            return try {
                val obj = JSONObject(raw)
                obj.keys().asSequence().associateWith { obj.getString(it) }
            } catch (_: Exception) {
                emptyMap()
            }
        }
    }
}
