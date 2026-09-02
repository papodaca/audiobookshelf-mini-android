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
}
