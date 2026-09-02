package io.github.miniaudiobookshelf.core

enum class CredentialMode {
    NONE,
    PASSWORD,
    API_KEY,
}

interface TokenStore {
    suspend fun save(origin: String, secret: String, mode: CredentialMode)
    suspend fun clear(origin: String)
    suspend fun load(origin: String): Pair<String, CredentialMode>?
}

class MemoryTokenStore : TokenStore {
    var failSave: Boolean = false
    var observe: TokenProvider? = null
    var snapshotAccess: String? = null
    val size: Int get() = secrets.size

    private val secrets = mutableMapOf<String, String>()
    private val modes = mutableMapOf<String, CredentialMode>()

    override suspend fun save(origin: String, secret: String, mode: CredentialMode) {
        snapshotAccess = observe?.accessToken()
        if (failSave) {
            throw SecretStoreException("keyring unavailable")
        }
        secrets[origin] = secret
        modes[origin] = mode
    }

    override suspend fun clear(origin: String) {
        secrets.remove(origin)
        modes.remove(origin)
    }

    override suspend fun load(origin: String): Pair<String, CredentialMode>? {
        val secret = secrets[origin] ?: return null
        return secret to (modes[origin] ?: CredentialMode.NONE)
    }

    fun loadSync(origin: String): String? = secrets[origin]
}

class FailingTokenStore : TokenStore {
    override suspend fun save(origin: String, secret: String, mode: CredentialMode) {
        throw SecretStoreException("encrypted store unavailable")
    }

    override suspend fun clear(origin: String) {}

    override suspend fun load(origin: String): Pair<String, CredentialMode>? = null
}

class SecretStoreException(message: String) : Exception(message)
