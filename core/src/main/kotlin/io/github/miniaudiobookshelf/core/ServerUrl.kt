package io.github.miniaudiobookshelf.core

import java.net.URI
import java.util.Locale

class ServerUrl private constructor(
    val scheme: String,
    val origin: String,
    val normalized: String,
) {
    val isHttps: Boolean get() = scheme == "https"
    val isHttp: Boolean get() = scheme == "http"
    val schemeLess: Boolean get() = scheme.isEmpty()

    fun join(path: String): String {
        if (path.isEmpty()) {
            return origin
        }
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return path
        }
        return if (path.startsWith("/")) origin + path else "$origin/$path"
    }

    fun sameOrigin(otherUri: String): Boolean {
        return try {
            parse(otherUri).origin == origin
        } catch (_: ApiError) {
            false
        }
    }

    companion object {
        fun parse(input: String): ServerUrl {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) {
                throw ApiError.InvalidUrl("Enter a server URL.")
            }

            if (!trimmed.contains("://")) {
                var normalized = trimmed
                while (normalized.endsWith("/")) {
                    normalized = normalized.dropLast(1)
                }
                return ServerUrl(
                    scheme = "",
                    origin = normalized.lowercase(Locale.ROOT),
                    normalized = normalized,
                )
            }

            val uri = try {
                URI(trimmed)
            } catch (_: Exception) {
                throw ApiError.InvalidUrl("Enter a valid server URL.")
            }

            val scheme = (uri.scheme ?: "").lowercase(Locale.ROOT)
            if (scheme != "http" && scheme != "https") {
                throw ApiError.InvalidUrl("Enter a valid server URL.")
            }

            val host = (uri.host ?: "").lowercase(Locale.ROOT)
            if (host.isEmpty()) {
                throw ApiError.InvalidUrl("Enter a valid server URL.")
            }

            val port = uri.port
            val elide = (scheme == "https" && (port == 443 || port == -1)) ||
                (scheme == "http" && (port == 80 || port == -1))

            var origin = "$scheme://$host"
            if (!elide && port > 0) {
                origin += ":$port"
            }

            return ServerUrl(scheme = scheme, origin = origin, normalized = origin)
        }
    }
}
