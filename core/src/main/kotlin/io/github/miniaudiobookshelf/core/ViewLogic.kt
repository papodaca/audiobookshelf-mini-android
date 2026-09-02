package io.github.miniaudiobookshelf.core

enum class ConnectDecision {
    PROCEED,
    TOO_OLD,
}

enum class ConnectFailure {
    UNREACHABLE,
    NOT_ABS,
    TOO_OLD,
    BAD_PASSWORD,
    REJECTED_API_KEY,
    RATE_LIMITED,
    HTTP_WARNING,
    TLS_UNTRUSTED,
}

data class AuthOffer(
    val password: Boolean,
    val apiKey: Boolean,
)

object ViewLogic {
    fun authOffer(status: ServerStatus): AuthOffer {
        return AuthOffer(password = status.localAuth, apiKey = true)
    }

    fun versionDecision(serverVersion: String): ConnectDecision {
        return if (ServerVersion.isSupported(serverVersion)) {
            ConnectDecision.PROCEED
        } else {
            ConnectDecision.TOO_OLD
        }
    }

    fun messageId(failure: ConnectFailure): String {
        return when (failure) {
            ConnectFailure.UNREACHABLE -> "connect.unreachable"
            ConnectFailure.NOT_ABS -> "connect.not-abs"
            ConnectFailure.TOO_OLD -> "connect.too-old"
            ConnectFailure.BAD_PASSWORD -> "connect.bad-password"
            ConnectFailure.REJECTED_API_KEY -> "connect.rejected-api-key"
            ConnectFailure.RATE_LIMITED -> "connect.rate-limited"
            ConnectFailure.HTTP_WARNING -> "connect.http-warning"
            ConnectFailure.TLS_UNTRUSTED -> "connect.tls-untrusted"
        }
    }

    fun userMessage(failure: ConnectFailure): String {
        return when (failure) {
            ConnectFailure.UNREACHABLE -> "The server could not be reached."
            ConnectFailure.NOT_ABS -> "That URL does not look like an Audiobookshelf server."
            ConnectFailure.TOO_OLD -> "This server is older than 2.26.0 and is not supported."
            ConnectFailure.BAD_PASSWORD -> "The username or password was rejected."
            ConnectFailure.REJECTED_API_KEY ->
                "The API key was rejected. It may be revoked or expired; an admin can issue a new one in the server UI."
            ConnectFailure.RATE_LIMITED ->
                "Too many sign-in attempts. Wait for the countdown before trying again."
            ConnectFailure.HTTP_WARNING ->
                "This address is not HTTPS. Your password and access token will travel unencrypted, and audio and cover art can be altered in transit by anyone on the path."
            ConnectFailure.TLS_UNTRUSTED ->
                "The server certificate could not be verified. You can pin this exact certificate for this origin, or refuse the connection."
        }
    }

    fun classifyApiError(error: Throwable, apiKeyPath: Boolean): ConnectFailure {
        return when (error) {
            is ApiError.ServerTooOld -> ConnectFailure.TOO_OLD
            is ApiError.Unauthorized -> if (apiKeyPath) {
                ConnectFailure.REJECTED_API_KEY
            } else {
                ConnectFailure.BAD_PASSWORD
            }
            is ApiError.RateLimited -> ConnectFailure.RATE_LIMITED
            is ApiError.Tls -> ConnectFailure.TLS_UNTRUSTED
            is ApiError.InvalidUrl, is ApiError.NotFound, is ApiError.Server -> ConnectFailure.NOT_ABS
            else -> ConnectFailure.UNREACHABLE
        }
    }
}
