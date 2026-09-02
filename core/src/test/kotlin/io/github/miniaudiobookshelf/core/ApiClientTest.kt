package io.github.miniaudiobookshelf.core

import java.util.ArrayDeque
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class FakeTransport : HttpTransport {
    data class Request(
        val method: String,
        val uri: String,
        val headers: Map<String, String>,
        val body: String?,
    )

    val requests = mutableListOf<Request>()
    private val queue = ArrayDeque<HttpResponse>()

    fun enqueue(status: Int, body: String, location: String? = null) {
        queue.add(HttpResponse(status = status, body = body, location = location))
    }

    override suspend fun send(
        method: String,
        uri: String,
        headers: Map<String, String>,
        body: String?,
    ): HttpResponse {
        requests.add(Request(method, uri, headers, body))
        if (queue.isEmpty()) {
            throw ApiError.Transport("no queued response")
        }
        return queue.removeFirst()
    }
}

class FakeTokenProvider(private val token: String) : TokenProvider {
    override fun accessToken(): String = token
}

private fun clientWith(
    transport: FakeTransport,
    tokens: TokenProvider? = null,
): ApiClient {
    return ApiClient(ServerUrl.parse("https://abs.example.com"), transport, tokens)
}

class ApiClientTest {
    @Test
    fun probeRejectsOld() = runTest {
        val transport = FakeTransport()
        transport.enqueue(200, """{"serverVersion":"2.25.9","authMethods":["local"]}""")
        val client = clientWith(transport)
        assertThrows<ApiError.ServerTooOld> { client.probeStatus() }
    }

    @Test
    fun probeAcceptsFloor() = runTest {
        val transport = FakeTransport()
        transport.enqueue(200, """{"serverVersion":"2.26.0","authMethods":["local"]}""")
        val client = clientWith(transport)
        val status = client.probeStatus()
        assertEquals("2.26.0", status.version)
        assertTrue(status.localAuth)
    }

    @Test
    fun probeIgnoresUnknownKeys() = runTest {
        val transport = FakeTransport()
        transport.enqueue(
            200,
            """{"serverVersion":"2.30.0","authMethods":["openid"],"init":true,"extra":{"n":1}}""",
        )
        val status = clientWith(transport).probeStatus()
        assertEquals("2.30.0", status.version)
        assertFalse(status.localAuth)
    }

    @Test
    fun login429IsRateLimited() = runTest {
        val transport = FakeTransport()
        transport.enqueue(429, """{"error":"Too many"}""")
        assertThrows<ApiError.RateLimited> { clientWith(transport).login("root", "pass") }
    }

    @Test
    fun login401IsUnauthorized() = runTest {
        val transport = FakeTransport()
        transport.enqueue(401, """{"error":"Unauthorized"}""")
        assertThrows<ApiError.Unauthorized> { clientWith(transport).login("root", "pass") }
    }

    @Test
    fun authorizedCallUsesFreshToken() = runTest {
        val transport = FakeTransport()
        transport.enqueue(200, "")
        val tokens = object : TokenProvider {
            override fun accessToken(): String = "stale"
            override suspend fun freshToken(): String = "fresh-token"
        }
        clientWith(transport, tokens).logout()
        assertEquals("Bearer fresh-token", transport.requests.single().headers["Authorization"])
    }

    @Test
    fun loginSendsReturnTokensHeaderAndJsonBody() = runTest {
        val transport = FakeTransport()
        transport.enqueue(200, """{"user":{"token":"a","refreshToken":"r"}}""")
        clientWith(transport).login("root", "s3cret")
        val req = transport.requests.single()
        assertEquals("POST", req.method)
        assertTrue(req.uri.endsWith("/login"))
        assertEquals("true", req.headers["x-return-tokens"])
        assertFalse(req.uri.contains("s3cret"))
        assertFalse(req.uri.contains("root"))
        assertTrue(req.body!!.contains("\"username\":\"root\""))
        assertTrue(req.body.contains("\"password\":\"s3cret\""))
    }

    @Test
    fun refreshUsesHeaderNotBearerOrQuery() = runTest {
        val transport = FakeTransport()
        transport.enqueue(200, """{"user":{"token":"a2","refreshToken":"r2"}}""")
        clientWith(transport).refresh("refresh-secret")
        val req = transport.requests.single()
        assertEquals("refresh-secret", req.headers["x-refresh-token"])
        assertFalse(req.headers.containsKey("Authorization"))
        assertFalse(req.uri.contains("refresh-secret"))
    }

    @Test
    fun crossOriginRedirectDropsAuthorization() = runTest {
        val transport = FakeTransport()
        transport.enqueue(302, "", "https://evil.example/steal")
        transport.enqueue(200, "")
        val client = clientWith(transport, FakeTokenProvider("secret-access-token"))
        client.logout()
        assertEquals(2, transport.requests.size)
        assertTrue(transport.requests[0].headers.containsKey("Authorization"))
        assertFalse(transport.requests[1].headers.containsKey("Authorization"))
        assertTrue(transport.requests[1].uri.startsWith("https://evil.example"))
    }

    @Test
    fun logLineRedactsSecrets() {
        val line = ApiClient.formatLogLine(
            "POST",
            "https://abs.example.com/auth/refresh",
            mapOf(
                "Authorization" to "Bearer secret-access-token",
                "x-refresh-token" to "refresh-secret",
                "Cookie" to "session=abc",
            ),
        )
        assertFalse(line.contains("secret-access-token"))
        assertFalse(line.contains("refresh-secret"))
        assertFalse(line.contains("session=abc"))
        assertTrue(line.contains("[redacted]"))
    }

    @Test
    fun inProgressUrlEncodesFilterExactly() {
        val path = Models.inProgressUrlPath("lib books/1", 25)
        assertTrue(path.contains("filter=progress.aW4tcHJvZ3Jlc3M%3D"))
        assertTrue(path.contains("/api/libraries/lib%20books%2F1/items"))
        assertTrue(path.contains("sort=progress"))
        assertTrue(path.contains("desc=1"))
        assertTrue(path.contains("limit=25"))
        assertTrue(path.contains("minified=1"))
    }

    @Test
    fun itemAndSearchAndRecentPathsEncode() {
        assertEquals(
            "/api/items/item-1?expanded=1&include=progress",
            Models.itemUrlPath("item-1"),
        )
        val episode = Models.itemUrlPath("item pod", "ep 1")
        assertTrue(episode.contains("/api/items/item%20pod?expanded=1&include=progress"))
        assertTrue(episode.contains("episode=ep%201"))
        val recent = Models.recentEpisodesUrlPath("lib pods/1", 0, 50)
        assertTrue(recent.contains("/api/libraries/lib%20pods%2F1/recent-episodes"))
        assertTrue(recent.contains("limit=50"))
        assertTrue(recent.contains("page=0"))
        val search = Models.searchUrlPath("lib-books", "dune & co")
        assertTrue(search.contains("/api/libraries/lib-books/search?q="))
        assertFalse(search.contains("token="))
    }

    @Test
    fun coverUrlIsJpegCacheBusterWithoutToken() {
        val transport = FakeTransport()
        val clock = FakeClock(1_700_000_000L)
        val client = ApiClient(
            ServerUrl.parse("https://abs.example.com"),
            transport,
            FakeTokenProvider("secret-access-token"),
            clock,
        )
        val url = client.coverUrl("item 1")
        assertTrue(url.contains("/api/items/item%201/cover"))
        assertTrue(url.contains("format=jpeg"))
        assertTrue(url.contains("ts=1700000000"))
        assertFalse(url.contains("token"))
        assertFalse(url.contains("secret-access-token"))
    }

    @Test
    fun fetchLibrariesSendsBearerHeader() = runTest {
        val transport = FakeTransport()
        transport.enqueue(200, """{"libraries":[{"id":"lib-books","name":"Books","mediaType":"book"}]}""")
        val client = clientWith(transport, FakeTokenProvider("secret-access-token"))
        val list = client.fetchLibraries()
        assertEquals("lib-books", list.libraries.single().id)
        assertEquals("Bearer secret-access-token", transport.requests.single().headers["Authorization"])
        assertFalse(transport.requests.single().uri.contains("token"))
    }

    @Test
    fun playUrlPathAndEpisodePath() {
        assertEquals("/api/items/item-1/play", ApiClient.playUrlPath("item-1"))
        assertEquals(
            "/api/items/item%201/play/ep%202",
            ApiClient.playUrlPath("item 1", "ep 2"),
        )
    }

    @Test
    fun streamUrlIsPublicAndUncredentialed() {
        val client = clientWith(FakeTransport(), FakeTokenProvider("secret-access-token"))
        val url = client.streamUrl("sess-abc-123", 1)
        assertEquals("https://abs.example.com/public/session/sess-abc-123/track/1", url)
        assertFalse(url.contains("token"))
        assertFalse(url.contains("secret-access-token"))
        assertFalse(url.contains("Authorization"))
    }

    @Test
    fun fileDownloadUrlIsAuthenticatedPathWithoutQueryToken() {
        val client = clientWith(FakeTransport(), FakeTokenProvider("secret-access-token"))
        val url = client.fileDownloadUrl("item-single", "123")
        assertEquals("https://abs.example.com/api/items/item-single/file/123/download", url)
        assertFalse(url.contains("token="))
        assertFalse(url.contains("secret-access-token"))
        assertFalse(url.contains("/public/session/"))
    }

    @Test
    fun syncLocalAllPostsFullSessionObjects() = runTest {
        val transport = FakeTransport()
        transport.enqueue(200, """{"results":[{"id":"11111111-1111-4111-8111-111111111111","success":true}]}""")
        val client = clientWith(transport, FakeTokenProvider("secret-access-token"))
        val session = LocalPlaybackSession(
            id = "11111111-1111-4111-8111-111111111111",
            libraryItemId = "item-1",
            currentTime = 12.0,
            timeListening = 12.0,
            displayTitle = "Book",
            deviceInfo = LocalDeviceInfo(deviceId = "dev-1"),
        )
        val results = client.syncLocalSessions(listOf(session))
        assertTrue(results.single().success)
        val req = transport.requests.single()
        assertTrue(req.uri.endsWith("/api/session/local-all"))
        assertEquals("Bearer secret-access-token", req.headers["Authorization"])
        assertTrue(req.body!!.contains("\"sessions\""))
        assertFalse(req.uri.contains("token="))
    }

    @Test
    fun playPostsForceDirectPlayAndDeviceInfo() = runTest {
        val transport = FakeTransport()
        transport.enqueue(200, fixture("play-session.json"))
        val client = clientWith(transport, FakeTokenProvider("secret-access-token"))
        val session = client.play("item-single", "device-1")
        assertEquals("sess-abc-123", session.id)
        val req = transport.requests.single()
        assertEquals("POST", req.method)
        assertTrue(req.uri.endsWith("/api/items/item-single/play"))
        assertEquals("Bearer secret-access-token", req.headers["Authorization"])
        val body = req.body!!
        assertTrue(body.contains("\"forceDirectPlay\":true"))
        assertTrue(body.contains("\"clientName\":\"Mini Audiobookshelf\""))
        assertTrue(body.contains("\"deviceId\":\"device-1\""))
    }

    @Test
    fun playEpisodeUsesNestedPath() = runTest {
        val transport = FakeTransport()
        transport.enqueue(200, fixture("play-session.json"))
        clientWith(transport, FakeTokenProvider("t")).play("item-pod", "device-1", "ep-1")
        assertTrue(transport.requests.single().uri.endsWith("/api/items/item-pod/play/ep-1"))
    }

    @Test
    fun syncAndCloseUseSessionPath() = runTest {
        val transport = FakeTransport()
        transport.enqueue(200, "{}")
        transport.enqueue(200, "{}")
        val client = clientWith(transport, FakeTokenProvider("t"))
        client.syncSession("sess-abc-123", 12.5, 3600.0, 10.0)
        client.closeSession("sess-abc-123")
        val syncBody = transport.requests[0].body!!
        assertTrue(transport.requests[0].uri.endsWith("/api/session/sess-abc-123/sync"))
        assertTrue(syncBody.contains("\"currentTime\":"))
        assertTrue(syncBody.contains("\"timeListened\":"))
        assertTrue(transport.requests[1].uri.endsWith("/api/session/sess-abc-123/close"))
        assertEquals("{}", transport.requests[1].body)
    }

    @Test
    fun logLineRedactsSessionIds() {
        val stream = ApiClient.formatLogLine(
            "GET",
            "https://abs.example.com/public/session/sess-abc-123/track/1",
            emptyMap(),
        )
        val sync = ApiClient.formatLogLine(
            "POST",
            "https://abs.example.com/api/session/sess-abc-123/sync",
            emptyMap(),
        )
        assertFalse(stream.contains("sess-abc-123"))
        assertFalse(sync.contains("sess-abc-123"))
        assertTrue(stream.contains("/public/session/[redacted]/track/1"))
        assertTrue(sync.contains("/api/session/[redacted]/sync"))
    }
}

private fun fixture(name: String): String {
    return ApiClientTest::class.java.classLoader!!.getResource("fixtures/$name")!!.readText()
}
