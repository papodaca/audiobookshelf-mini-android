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
    private var allowMeteredDownloads: Boolean = false

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
        allowMeteredDownloads = prefs[ALLOW_METERED] ?: false
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

    override fun getAllowMeteredDownloads(): Boolean = synchronized(lock) { allowMeteredDownloads }

    override fun setAllowMeteredDownloads(value: Boolean) {
        synchronized(lock) { allowMeteredDownloads = value }
        persist { it[ALLOW_METERED] = value }
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
        private val ALLOW_METERED = booleanPreferencesKey("allow_metered_downloads")

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
