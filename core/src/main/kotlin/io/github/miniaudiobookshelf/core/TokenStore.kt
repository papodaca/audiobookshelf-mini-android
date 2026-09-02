package io.github.miniaudiobookshelf.core

enum class CredentialMode {
    NONE,
    PASSWORD,
    API_KEY,
}

data class StoredSecret(
    val secret: String,
    val mode: CredentialMode,
    val access: String? = null,
)

interface TokenStore {
    suspend fun save(origin: String, secret: String, mode: CredentialMode, access: String? = null)
    suspend fun clear(origin: String)
    suspend fun load(origin: String): StoredSecret?
}

class MemoryTokenStore : TokenStore {
    var failSave: Boolean = false
    var observe: TokenProvider? = null
    var snapshotAccess: String? = null
    val size: Int get() = secrets.size

    private val secrets = mutableMapOf<String, String>()
    private val modes = mutableMapOf<String, CredentialMode>()
    private val accesses = mutableMapOf<String, String>()

    override suspend fun save(origin: String, secret: String, mode: CredentialMode, access: String?) {
        snapshotAccess = observe?.accessToken()
        if (failSave) {
            throw SecretStoreException("keyring unavailable")
        }
        secrets[origin] = secret
        modes[origin] = mode
        if (access.isNullOrEmpty()) {
            accesses.remove(origin)
        } else {
            accesses[origin] = access
        }
    }

    override suspend fun clear(origin: String) {
        secrets.remove(origin)
        modes.remove(origin)
        accesses.remove(origin)
    }

    override suspend fun load(origin: String): StoredSecret? {
        val secret = secrets[origin] ?: return null
        return StoredSecret(secret, modes[origin] ?: CredentialMode.NONE, accesses[origin])
    }

    fun loadSync(origin: String): String? = secrets[origin]
}

class FailingTokenStore : TokenStore {
    override suspend fun save(origin: String, secret: String, mode: CredentialMode, access: String?) {
        throw SecretStoreException("encrypted store unavailable")
    }

    override suspend fun clear(origin: String) {}

    override suspend fun load(origin: String): StoredSecret? = null
}

class SecretStoreException(message: String) : Exception(message)
