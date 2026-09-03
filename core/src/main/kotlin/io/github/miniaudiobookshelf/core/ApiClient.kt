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
    private val clock: Clock = SystemClock(),
) : AuthEndpoints {
    fun setTokenProvider(tokens: TokenProvider) {
        this.tokens = tokens
    }

    fun coverUrl(itemId: String): String {
        val ts = clock.unixSeconds().toString()
        return server.join("/api/items/${UriEscape.escape(itemId)}/cover?format=jpeg&ts=$ts")
    }

    suspend fun fetchAllProgress(): List<MediaProgress> {
        val body = send("GET", "/api/me/progress", extraHeaders = emptyMap(), jsonBody = null, attachAuth = true)
        return Models.parseProgressList(body)
    }

    suspend fun fetchLibraries(): LibraryList {
        val body = send("GET", "/api/libraries", extraHeaders = emptyMap(), jsonBody = null, attachAuth = true)
        return Models.parseLibraryList(body)
    }

    suspend fun fetchInProgress(libraryId: String, limit: Int = 25): ItemPage {
        val body = send("GET", Models.inProgressUrlPath(libraryId, limit), extraHeaders = emptyMap(), jsonBody = null, attachAuth = true)
        val page = Models.parseItemPage(body)
        return page.copy(results = ViewLogic.bookItems(page.results))
    }

    suspend fun fetchRecentEpisodes(libraryId: String, limit: Int = 50): EpisodePage {
        val body = send(
            "GET",
            Models.recentEpisodesUrlPath(libraryId, page = 0, limit = limit),
            extraHeaders = emptyMap(),
            jsonBody = null,
            attachAuth = true,
        )
        return Models.parseEpisodePage(body)
    }

    suspend fun fetchItem(itemId: String, episodeId: String? = null): LibraryItem {
        val body = send("GET", Models.itemUrlPath(itemId, episodeId), extraHeaders = emptyMap(), jsonBody = null, attachAuth = true)
        return Models.parseItem(body)
    }

    suspend fun fetchLibraryItems(libraryId: String, page: Int = 0, limit: Int = 50): ItemPage {
        val body = send("GET", Models.libraryItemsUrlPath(libraryId, page, limit), extraHeaders = emptyMap(), jsonBody = null, attachAuth = true)
        return Models.parseItemPage(body)
    }

    suspend fun search(libraryId: String, query: String): String {
        return send("GET", Models.searchUrlPath(libraryId, query), extraHeaders = emptyMap(), jsonBody = null, attachAuth = true)
    }

    fun streamUrl(sessionId: String, index: Int): String {
        return server.join("/public/session/${UriEscape.escape(sessionId)}/track/$index")
    }

    fun fileDownloadUrl(itemId: String, ino: String): String {
        return server.join(DownloadFiles.fileDownloadPath(itemId, ino))
    }

    suspend fun syncLocalSessions(sessions: List<LocalPlaybackSession>): List<LocalSyncResult> {
        val body = send(
            "POST",
            "/api/session/local-all",
            extraHeaders = emptyMap(),
            jsonBody = LocalSessionCodec.requestBody(sessions),
            attachAuth = true,
        )
        return LocalSessionCodec.parseResults(body)
    }

    suspend fun play(itemId: String, deviceId: String, episodeId: String? = null): PlaySession {
        val path = playUrlPath(itemId, episodeId)
        val payload = json.encodeToString(
            PlayRequest.serializer(),
            PlayRequest(deviceInfo = PlayDeviceInfo(deviceId = deviceId)),
        )
        val body = send("POST", path, extraHeaders = emptyMap(), jsonBody = payload, attachAuth = true)
        return PlaySession.parse(body)
    }

    suspend fun syncSession(sessionId: String, currentTime: Double, duration: Double, timeListened: Double) {
        val path = "/api/session/${UriEscape.escape(sessionId)}/sync"
        val payload = json.encodeToString(
            SyncRequest.serializer(),
            SyncRequest(currentTime = currentTime, duration = duration, timeListened = timeListened),
        )
        send("POST", path, extraHeaders = emptyMap(), jsonBody = payload, attachAuth = true)
    }

    suspend fun closeSession(sessionId: String) {
        val path = "/api/session/${UriEscape.escape(sessionId)}/close"
        send("POST", path, extraHeaders = emptyMap(), jsonBody = "{}", attachAuth = true)
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

    suspend fun authorize(): String {
        return send("GET", "/api/authorize", extraHeaders = emptyMap(), jsonBody = null, attachAuth = true)
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
            val token = tokens?.freshToken()
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
            builder.append(method).append(' ').append(redactUri(uri))
            for ((name, value) in headers) {
                builder.append(' ').append(name).append('=').append(redactHeaderValue(name, value))
            }
            return builder.toString()
        }

        fun redactUri(uri: String): String {
            return uri
                .replace(Regex("/public/session/[^/]+/"), "/public/session/[redacted]/")
                .replace(Regex("/api/session/[^/]+/"), "/api/session/[redacted]/")
        }

        fun playUrlPath(itemId: String, episodeId: String? = null): String {
            return if (!episodeId.isNullOrEmpty()) {
                "/api/items/${UriEscape.escape(itemId)}/play/${UriEscape.escape(episodeId)}"
            } else {
                "/api/items/${UriEscape.escape(itemId)}/play"
            }
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

@Serializable
private data class PlayRequest(
    val deviceInfo: PlayDeviceInfo,
    val forceDirectPlay: Boolean = true,
)

@Serializable
private data class PlayDeviceInfo(
    val clientName: String = "Mini Audiobookshelf",
    val deviceId: String,
)

@Serializable
private data class SyncRequest(
    val currentTime: Double,
    val duration: Double,
    val timeListened: Double,
)
