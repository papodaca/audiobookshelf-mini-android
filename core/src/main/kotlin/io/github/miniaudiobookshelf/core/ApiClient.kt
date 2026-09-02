package io.github.miniaudiobookshelf.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

class ApiClient(
    val server: ServerUrl,
    private val transport: HttpTransport,
    private var tokens: TokenProvider? = null,
) : AuthEndpoints {
    fun setTokenProvider(tokens: TokenProvider) {
        this.tokens = tokens
    }

    suspend fun probeStatus(): ServerStatus {
        val body = send("GET", "/status", extraHeaders = emptyMap(), jsonBody = null, attachAuth = false)
        val root = parseObject(body, "status")
        val version = root["serverVersion"]?.jsonPrimitive?.content ?: ""
        val methods = mutableListOf<String>()
        val methodsElement = root["authMethods"]
        if (methodsElement != null && methodsElement !is kotlinx.serialization.json.JsonNull) {
            methodsElement.jsonArray.forEach { methods.add(it.jsonPrimitive.content) }
        }
        val status = ServerStatus(version = version, authMethods = methods)
        if (!ServerVersion.isSupported(status.version)) {
            throw ApiError.ServerTooOld(
                "server version ${status.version} is below ${ServerVersion.MINIMUM}",
            )
        }
        return status
    }

    override suspend fun login(username: String, password: String): String {
        val payload = json.encodeToString(LoginBody.serializer(), LoginBody(username, password))
        return send(
            "POST",
            "/login",
            extraHeaders = mapOf("x-return-tokens" to "true"),
            jsonBody = payload,
            attachAuth = false,
        )
    }

    override suspend fun refresh(refreshToken: String): String {
        return send(
            "POST",
            "/auth/refresh",
            extraHeaders = mapOf("x-refresh-token" to refreshToken),
            jsonBody = "{}",
            attachAuth = false,
        )
    }

    override suspend fun logout() {
        send("POST", "/logout", extraHeaders = emptyMap(), jsonBody = null, attachAuth = true)
    }

    suspend fun authorize() {
        send("GET", "/api/authorize", extraHeaders = emptyMap(), jsonBody = null, attachAuth = true)
    }

    private suspend fun send(
        method: String,
        path: String,
        extraHeaders: Map<String, String>,
        jsonBody: String?,
        attachAuth: Boolean,
    ): String {
        val uri = if (path.startsWith("http://") || path.startsWith("https://")) {
            path
        } else {
            server.join(path)
        }
        val headers = extraHeaders.toMutableMap()
        if (attachAuth) {
            val token = tokens?.accessToken()
            if (!token.isNullOrEmpty()) {
                headers["Authorization"] = "Bearer $token"
            }
        }
        return sendFollowRedirects(method, uri, jsonBody, headers, hop = 0)
    }

    private suspend fun sendFollowRedirects(
        method: String,
        uri: String,
        jsonBody: String?,
        headers: Map<String, String>,
        hop: Int,
    ): String {
        if (hop > 5) {
            throw ApiError.Transport("too many redirects")
        }
        val response = try {
            transport.send(method, uri, headers, jsonBody)
        } catch (e: TlsHandshakeException) {
            throw ApiError.Tls(e.message ?: "tls validation failed")
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: ApiError) {
            throw e
        } catch (_: Exception) {
            throw ApiError.Transport("request failed")
        }

        val location = response.location
        if (response.status in 300..399 && location != null) {
            val next = if (location.startsWith("/")) server.join(location) else location
            val nextHeaders = headers.toMutableMap()
            if (!server.sameOrigin(next)) {
                nextHeaders.remove("Authorization")
                nextHeaders.remove("authorization")
            }
            return sendFollowRedirects(method, next, jsonBody, nextHeaders, hop + 1)
        }

        when {
            response.status == 401 || response.status == 403 -> throw ApiError.Unauthorized("unauthorized")
            response.status == 429 -> throw ApiError.RateLimited("rate limited")
            response.status == 404 -> throw ApiError.NotFound("not found")
            response.status >= 500 -> throw ApiError.Server("server error")
            response.status >= 400 -> throw ApiError.Server("request failed")
        }
        return response.body
    }

    companion object {
        val json: Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }

        fun redactHeaderValue(name: String, value: String): String {
            val key = name.lowercase(java.util.Locale.ROOT)
            return if (key == "authorization" || key == "cookie" || key == "x-refresh-token") {
                "[redacted]"
            } else {
                value
            }
        }

        fun formatLogLine(method: String, uri: String, headers: Map<String, String>): String {
            val builder = StringBuilder()
            builder.append(method).append(' ').append(uri)
            for ((name, value) in headers) {
                builder.append(' ').append(name).append('=').append(redactHeaderValue(name, value))
            }
            return builder.toString()
        }

        private fun parseObject(body: String, label: String): JsonObject {
            return try {
                json.parseToJsonElement(body) as? JsonObject
                    ?: throw ApiError.Server("invalid $label json")
            } catch (e: ApiError) {
                throw e
            } catch (_: Exception) {
                throw ApiError.Server("invalid $label json")
            }
        }
    }
}

@Serializable
private data class LoginBody(
    val username: String,
    val password: String,
)
