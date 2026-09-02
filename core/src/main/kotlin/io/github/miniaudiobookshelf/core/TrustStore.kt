package io.github.miniaudiobookshelf.core

class TrustStore(private val settings: SettingsStore) {
    fun pin(origin: String, pem: String) {
        settings.setPinnedCertificate(origin, pem)
    }

    fun pemFor(origin: String): String? {
        return settings.getPinnedCertificates()[origin]
    }

    fun matches(origin: String, pem: String): Boolean {
        val stored = pemFor(origin) ?: return false
        return stored.trim() == pem.trim()
    }
}
