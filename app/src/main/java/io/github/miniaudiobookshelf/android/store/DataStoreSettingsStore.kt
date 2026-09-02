package io.github.miniaudiobookshelf.android.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.miniaudiobookshelf.core.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

private val Context.mabsSettings: DataStore<Preferences> by preferencesDataStore(name = "mabs_settings")

class DataStoreSettingsStore(context: Context) : SettingsStore {
    private val dataStore = context.applicationContext.mabsSettings
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var serverUrl: String = ""
    private var deviceId: String = ""
    private var pins: Map<String, String> = emptyMap()
    private var httpAck: Set<String> = emptySet()

    init {
        val prefs = runBlocking { dataStore.data.first() }
        serverUrl = prefs[SERVER_URL].orEmpty()
        deviceId = prefs[DEVICE_ID].orEmpty()
        httpAck = prefs[HTTP_ACK].orEmpty()
        pins = decodePins(prefs[PINS].orEmpty())
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

    private fun persist(mutate: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        scope.launch {
            dataStore.edit(mutate)
        }
    }

    companion object {
        private val SERVER_URL = stringPreferencesKey("server_url")
        private val DEVICE_ID = stringPreferencesKey("device_id")
        private val HTTP_ACK = stringSetPreferencesKey("http_ack_origins")
        private val PINS = stringPreferencesKey("pinned_certificates")

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
