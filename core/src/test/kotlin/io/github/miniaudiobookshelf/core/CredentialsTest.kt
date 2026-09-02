package io.github.miniaudiobookshelf.core

import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class FakeClock(var now: Long = 1_700_000_000L) : Clock {
    override fun unixSeconds(): Long = now
}

class FakeAuthEndpoints : AuthEndpoints {
    var loginBody: String = ""
    var refreshBody: String = ""
    var loginError: ApiError? = null
    var refreshError: ApiError? = null
    var logoutFails: Boolean = false
    var loginCalls: Int = 0
    var refreshCalls: Int = 0
    var logoutCalls: Int = 0
    var holdRefresh: CompletableDeferred<Unit>? = null

    override suspend fun login(username: String, password: String): String {
        loginCalls += 1
        loginError?.let { throw it }
        return loginBody
    }

    override suspend fun refresh(refreshToken: String): String {
        refreshCalls += 1
        holdRefresh?.await()
        refreshError?.let { throw it }
        return refreshBody
    }

    override suspend fun logout() {
        logoutCalls += 1
        if (logoutFails) {
            throw ApiError.Transport("logout failed")
        }
    }
}

private fun authJson(access: String, refresh: String, expiresIn: Long = -1): String {
    return if (expiresIn < 0) {
        """{"user":{"token":"$access","refreshToken":"$refresh"}}"""
    } else {
        """{"user":{"token":"$access","refreshToken":"$refresh"},"expiresIn":$expiresIn}"""
    }
}

private fun managerWith(
    auth: FakeAuthEndpoints,
    store: MemoryTokenStore,
    settings: MemorySettingsStore,
    clock: FakeClock,
    origin: String = "https://abs.example.com",
): CredentialManager {
    return CredentialManager(auth, store, settings, clock, origin)
}

class CredentialsTest {
    @Test
    fun passwordLoginStoresRefreshOnly() = runTest {
        val auth = FakeAuthEndpoints().apply { loginBody = authJson("access-a", "refresh-a", 3600) }
        val store = MemoryTokenStore()
        val mgr = managerWith(auth, store, MemorySettingsStore(), FakeClock())
        mgr.signInPassword("root", "pass")
        assertEquals("access-a", mgr.accessToken())
        assertEquals("refresh-a", store.loadSync("https://abs.example.com"))
        assertFalse(store.loadSync("https://abs.example.com") == "access-a")
        assertEquals(CredentialState.VALID, mgr.state)
    }

    @Test
    fun refreshScheduleFollowsReportedLifetime() = runTest {
        val auth = FakeAuthEndpoints()
        val clock = FakeClock()
        val mgr = managerWith(auth, MemoryTokenStore(), MemorySettingsStore(), clock)
        auth.loginBody = authJson("a1", "r1", 3600)
        mgr.signInPassword("u", "p")
        val first = mgr.refreshAt
        auth.loginBody = authJson("a2", "r2", 7200)
        mgr.signInPassword("u", "p")
        val second = mgr.refreshAt
        assertEquals(3600, second - first)
        assertEquals(3600 - CredentialManager.REFRESH_MARGIN_SECONDS, first - clock.now)
    }

    @Test
    fun missingExpiryUsesFallback() = runTest {
        val auth = FakeAuthEndpoints().apply { loginBody = authJson("a", "r") }
        val clock = FakeClock()
        val mgr = managerWith(auth, MemoryTokenStore(), MemorySettingsStore(), clock)
        mgr.signInPassword("u", "p")
        assertEquals(
            CredentialManager.FALLBACK_ACCESS_LIFETIME_SECONDS - CredentialManager.REFRESH_MARGIN_SECONDS,
            mgr.refreshAt - clock.now,
        )
    }

    @Test
    fun refreshPersistsBeforeAccessSwap() = runTest {
        val auth = FakeAuthEndpoints().apply {
            loginBody = authJson("old-access", "old-refresh", 1)
            refreshBody = authJson("new-access", "new-refresh", 3600)
        }
        val store = MemoryTokenStore()
        val clock = FakeClock()
        val mgr = managerWith(auth, store, MemorySettingsStore(), clock)
        store.observe = mgr
        mgr.signInPassword("u", "p")
        clock.now = mgr.refreshAt + 1
        mgr.ensureFresh()
        assertEquals("old-access", store.snapshotAccess)
        assertEquals("new-access", mgr.accessToken())
        assertEquals("new-refresh", store.loadSync("https://abs.example.com"))
    }

    @Test
    fun concurrentRefreshIsSingleFlight() = runTest {
        val hold = CompletableDeferred<Unit>()
        val auth = FakeAuthEndpoints().apply {
            loginBody = authJson("a", "r", 1)
            refreshBody = authJson("a2", "r2", 3600)
            holdRefresh = hold
        }
        val clock = FakeClock()
        val mgr = managerWith(auth, MemoryTokenStore(), MemorySettingsStore(), clock)
        mgr.signInPassword("u", "p")
        clock.now = mgr.refreshAt + 1
        val first = async { mgr.ensureFresh() }
        val second = async { mgr.ensureFresh() }
        hold.complete(Unit)
        assertEquals("a2", first.await())
        assertEquals("a2", second.await())
        assertEquals(1, auth.refreshCalls)
    }

    @Test
    fun keyringFailureDegrades() = runTest {
        val auth = FakeAuthEndpoints().apply { loginBody = authJson("a", "r", 3600) }
        val store = MemoryTokenStore().apply { failSave = true }
        val mgr = managerWith(auth, store, MemorySettingsStore(), FakeClock())
        mgr.signInPassword("u", "p")
        assertTrue(mgr.degraded)
        assertEquals("a", mgr.accessToken())
        assertTrue(mgr.degradedNotice)
    }

    @Test
    fun rateLimitedRefreshDoesNotRetry() = runTest {
        val auth = FakeAuthEndpoints().apply {
            loginBody = authJson("a", "r", 1)
            refreshError = ApiError.RateLimited("slow down")
        }
        val clock = FakeClock()
        val mgr = managerWith(auth, MemoryTokenStore(), MemorySettingsStore(), clock)
        mgr.signInPassword("u", "p")
        clock.now = mgr.refreshAt + 1
        assertThrows<ApiError.RateLimited> { mgr.ensureFresh() }
        assertEquals(CredentialState.RATE_LIMITED, mgr.state)
        assertEquals(1, auth.refreshCalls)
        val scheduled = mgr.refreshAt
        clock.now += 30
        assertThrows<ApiError.RateLimited> { mgr.ensureFresh() }
        assertEquals(1, auth.refreshCalls)
        assertEquals(scheduled, mgr.refreshAt)
    }

    @Test
    fun rejectedRefreshClearsStore() = runTest {
        val auth = FakeAuthEndpoints().apply {
            loginBody = authJson("a", "r", 1)
            refreshError = ApiError.Unauthorized("nope")
        }
        val store = MemoryTokenStore()
        val clock = FakeClock()
        val mgr = managerWith(auth, store, MemorySettingsStore(), clock)
        mgr.signInPassword("u", "p")
        clock.now = mgr.refreshAt + 1
        assertThrows<ApiError.Unauthorized> { mgr.ensureFresh() }
        assertEquals(CredentialState.NO_CREDENTIAL, mgr.state)
        assertNull(store.loadSync("https://abs.example.com"))
    }

    @Test
    fun signOutRevokesAndClears() = runTest {
        val auth = FakeAuthEndpoints().apply { loginBody = authJson("a", "r", 3600) }
        val store = MemoryTokenStore()
        val mgr = managerWith(auth, store, MemorySettingsStore(), FakeClock())
        mgr.signInPassword("u", "p")
        mgr.signOut()
        assertEquals(1, auth.logoutCalls)
        assertNull(store.loadSync("https://abs.example.com"))
        assertEquals(CredentialState.NO_CREDENTIAL, mgr.state)
    }

    @Test
    fun signOutClearsWhenRevokeFails() = runTest {
        val auth = FakeAuthEndpoints().apply {
            loginBody = authJson("a", "r", 3600)
            logoutFails = true
        }
        val store = MemoryTokenStore()
        val mgr = managerWith(auth, store, MemorySettingsStore(), FakeClock())
        mgr.signInPassword("u", "p")
        mgr.signOut()
        assertNull(store.loadSync("https://abs.example.com"))
        assertEquals(CredentialState.NO_CREDENTIAL, mgr.state)
    }

    @Test
    fun apiKeyNeverRefreshes() = runTest {
        val auth = FakeAuthEndpoints()
        val store = MemoryTokenStore()
        val clock = FakeClock()
        val mgr = managerWith(auth, store, MemorySettingsStore(), clock)
        mgr.signInApiKey("api-key-value")
        assertEquals("api-key-value", mgr.accessToken())
        assertEquals("api-key-value", store.loadSync("https://abs.example.com"))
        assertEquals(0, mgr.refreshAt)
        clock.now += 100000
        mgr.ensureFresh()
        assertEquals(0, auth.refreshCalls)
        assertEquals(0, auth.loginCalls)
    }

    @Test
    fun originVariantsShareKey() = runTest {
        val a = ServerUrl.parse("https://ABS.example.com:443")
        val b = ServerUrl.parse("https://abs.example.com")
        assertEquals(a.origin, b.origin)
        val store = MemoryTokenStore()
        store.save(a.origin, "secret", CredentialMode.PASSWORD)
        store.save(b.origin, "secret-2", CredentialMode.PASSWORD)
        assertEquals(1, store.size)
        assertEquals("secret-2", store.loadSync(a.origin))
    }

    @Test
    fun deviceIdGeneratedOnce() {
        val settings = MemorySettingsStore()
        val clock = FakeClock()
        val auth = FakeAuthEndpoints()
        val store = MemoryTokenStore()
        val first = CredentialManager(auth, store, settings, clock, "https://abs.example.com")
        val id = first.ensureDeviceId()
        assertTrue(id.isNotEmpty())
        val second = CredentialManager(auth, store, settings, clock, "https://abs.example.com")
        assertEquals(id, second.ensureDeviceId())
    }

    @Test
    fun expiresInOnUserObjectIsAccepted() {
        val tokens = AuthTokens.parse(
            """{"user":{"token":"a","refreshToken":"r","expiresIn":1200}}""",
        )
        assertEquals("a", tokens.access)
        assertEquals("r", tokens.refresh)
        assertEquals(1200, tokens.expiresIn)
    }
}
