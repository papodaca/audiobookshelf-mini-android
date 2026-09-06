package io.github.miniaudiobookshelf.core

import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class CredentialState {
    NO_CREDENTIAL,
    PROBING,
    AUTHENTICATING,
    VALID,
    REFRESHING,
    RATE_LIMITED,
    REVOKING,
}

class CredentialManager(
    private val auth: AuthEndpoints,
    private val store: TokenStore,
    private val settings: SettingsStore,
    private val clock: Clock,
    private val origin: String,
) : TokenProvider {
    var state: CredentialState = CredentialState.NO_CREDENTIAL
        private set
    var mode: CredentialMode = CredentialMode.NONE
        private set
    var refreshAt: Long = 0
        private set
    var degraded: Boolean = false
        private set
    var degradedNotice: Boolean = false
        private set

    private var access: String? = null
    private var refreshToken: String? = null
    private val refreshMutex = Mutex()

    override fun accessToken(): String? = access

    override suspend fun freshToken(): String? = ensureFresh()

    fun ensureDeviceId(): String {
        var id = settings.getDeviceId()
        if (id.isEmpty()) {
            id = UUID.randomUUID().toString()
            settings.setDeviceId(id)
        }
        return id
    }

    fun consumeDegradedNotice(): Boolean {
        val shown = degradedNotice
        degradedNotice = false
        return shown
    }

    suspend fun signInPassword(username: String, password: String) {
        state = CredentialState.AUTHENTICATING
        mode = CredentialMode.PASSWORD
        val body = try {
            auth.login(username, password)
        } catch (e: ApiError.RateLimited) {
            state = CredentialState.RATE_LIMITED
            throw e
        } catch (e: ApiError.Unauthorized) {
            clearLocal()
            throw e
        } catch (e: ApiError) {
            state = CredentialState.NO_CREDENTIAL
            throw e
        }
        val tokens = try {
            AuthTokens.parse(body)
        } catch (e: ApiError) {
            state = CredentialState.NO_CREDENTIAL
            throw e
        }
        applyPasswordTokens(tokens)
    }

    suspend fun signInApiKey(key: String) {
        state = CredentialState.AUTHENTICATING
        mode = CredentialMode.API_KEY
        persist(key, CredentialMode.API_KEY, key)
        access = key
        refreshToken = null
        refreshAt = 0
        state = CredentialState.VALID
    }

    suspend fun ensureFresh(): String? {
        if (state == CredentialState.RATE_LIMITED) {
            throw ApiError.RateLimited("Authentication is rate limited.")
        }
        if (mode == CredentialMode.API_KEY || refreshAt == 0L || clock.unixSeconds() < refreshAt) {
            return access
        }
        return refreshMutex.withLock {
            if (state == CredentialState.RATE_LIMITED) {
                throw ApiError.RateLimited("Authentication is rate limited.")
            }
            if (state == CredentialState.NO_CREDENTIAL) {
                throw ApiError.Unauthorized("Sign-in was rejected.")
            }
            if (mode == CredentialMode.API_KEY || refreshAt == 0L || clock.unixSeconds() < refreshAt) {
                return@withLock access
            }
            state = CredentialState.REFRESHING
            rotate()
            access
        }
    }

    suspend fun signOut(logoutOnServer: Boolean = true) {
        state = CredentialState.REVOKING
        if (logoutOnServer) {
            try {
                auth.logout()
            } catch (_: Exception) {
            }
        }
        clearLocal()
    }

    suspend fun restore(): Boolean {
        val stored = store.load(origin) ?: return false
        if (stored.secret.isBlank()) return false
        if (stored.mode == CredentialMode.API_KEY) {
            access = stored.secret
            mode = CredentialMode.API_KEY
            refreshAt = 0
            state = CredentialState.VALID
            return true
        }
        refreshToken = stored.secret
        mode = CredentialMode.PASSWORD
        state = CredentialState.VALID
        if (!stored.access.isNullOrBlank()) {
            access = stored.access
            refreshAt = clock.unixSeconds()
            return true
        }
        refreshAt = clock.unixSeconds()
        return try {
            ensureFresh()
            state == CredentialState.VALID && !access.isNullOrBlank()
        } catch (_: ApiError) {
            false
        }
    }

    private suspend fun applyPasswordTokens(tokens: AuthTokens) {
        persist(tokens.refresh, CredentialMode.PASSWORD, tokens.access)
        access = tokens.access
        refreshToken = tokens.refresh
        mode = CredentialMode.PASSWORD
        scheduleFrom(tokens.expiresIn)
        state = CredentialState.VALID
    }

    private suspend fun rotate() {
        val currentRefresh = refreshToken
        if (currentRefresh == null) {
            clearLocalSilent()
            throw ApiError.Unauthorized("Sign-in was rejected.")
        }
        val body = try {
            auth.refresh(currentRefresh)
        } catch (e: ApiError.RateLimited) {
            state = CredentialState.RATE_LIMITED
            throw e
        } catch (e: ApiError.Unauthorized) {
            clearLocalSilent()
            throw e
        } catch (e: ApiError) {
            state = CredentialState.VALID
            throw e
        }
        val tokens = AuthTokens.parse(body)
        persist(tokens.refresh, CredentialMode.PASSWORD, tokens.access)
        access = tokens.access
        refreshToken = tokens.refresh
        scheduleFrom(tokens.expiresIn)
        state = CredentialState.VALID
    }

    private suspend fun persist(secret: String, storedMode: CredentialMode, accessToken: String? = null) {
        if (secret.isBlank()) {
            degraded = true
            degradedNotice = true
            return
        }
        try {
            store.save(origin, secret, storedMode, accessToken)
        } catch (_: Exception) {
            degraded = true
            degradedNotice = true
        }
    }

    private suspend fun clearLocal() {
        try {
            store.clear(origin)
        } catch (_: Exception) {
            degraded = true
        }
        access = null
        refreshToken = null
        refreshAt = 0
        mode = CredentialMode.NONE
        state = CredentialState.NO_CREDENTIAL
    }

    private suspend fun clearLocalSilent() {
        try {
            clearLocal()
        } catch (_: Exception) {
            access = null
            refreshToken = null
            refreshAt = 0
            mode = CredentialMode.NONE
            state = CredentialState.NO_CREDENTIAL
        }
    }

    private fun scheduleFrom(expiresIn: Long) {
        var lifetime = expiresIn
        if (lifetime < 0) {
            lifetime = FALLBACK_ACCESS_LIFETIME_SECONDS
        }
        var at = clock.unixSeconds() + lifetime - REFRESH_MARGIN_SECONDS
        if (at < clock.unixSeconds()) {
            at = clock.unixSeconds()
        }
        refreshAt = at
    }

    companion object {
        const val REFRESH_MARGIN_SECONDS = 60L
        const val FALLBACK_ACCESS_LIFETIME_SECONDS = 600L
    }
}
