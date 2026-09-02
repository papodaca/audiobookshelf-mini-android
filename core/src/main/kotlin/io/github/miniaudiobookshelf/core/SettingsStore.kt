package io.github.miniaudiobookshelf.core

interface SettingsStore {
    fun getServerUrl(): String
    fun setServerUrl(value: String)
    fun getDeviceId(): String
    fun setDeviceId(value: String)
    fun getPinnedCertificates(): Map<String, String>
    fun setPinnedCertificate(origin: String, pem: String)
    fun isHttpAcknowledged(origin: String): Boolean
    fun setHttpAcknowledged(origin: String, acknowledged: Boolean)
    fun getLibraryId(): String
    fun setLibraryId(value: String)
}

class MemorySettingsStore : SettingsStore {
    private var serverUrl: String = ""
    private var deviceId: String = ""
    private val pins = mutableMapOf<String, String>()
    private val httpAck = mutableSetOf<String>()
    private var libraryId: String = ""

    override fun getServerUrl(): String = serverUrl
    override fun setServerUrl(value: String) {
        serverUrl = value
    }

    override fun getDeviceId(): String = deviceId
    override fun setDeviceId(value: String) {
        deviceId = value
    }

    override fun getPinnedCertificates(): Map<String, String> = pins.toMap()

    override fun setPinnedCertificate(origin: String, pem: String) {
        pins[origin] = pem
    }

    override fun isHttpAcknowledged(origin: String): Boolean = origin in httpAck

    override fun setHttpAcknowledged(origin: String, acknowledged: Boolean) {
        if (acknowledged) httpAck.add(origin) else httpAck.remove(origin)
    }

    override fun getLibraryId(): String = libraryId

    override fun setLibraryId(value: String) {
        libraryId = value
    }
}
