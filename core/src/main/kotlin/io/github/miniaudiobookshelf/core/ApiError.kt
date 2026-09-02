package io.github.miniaudiobookshelf.core

sealed class ApiError(message: String) : Exception(message) {
    class InvalidUrl(message: String = "Enter a valid server URL.") : ApiError(message)
    class ServerTooOld(message: String) : ApiError(message)
    class Unauthorized(message: String = "unauthorized") : ApiError(message)
    class RateLimited(message: String = "rate limited") : ApiError(message)
    class NotFound(message: String = "not found") : ApiError(message)
    class Server(message: String = "server error") : ApiError(message)
    class Transport(message: String = "request failed") : ApiError(message)
    class Tls(message: String = "tls validation failed") : ApiError(message)
    class Cancelled(message: String = "cancelled") : ApiError(message)

    companion object {
        fun userMessage(error: Throwable): String {
            return when (error) {
                is Unauthorized -> "Sign-in was rejected."
                is RateLimited -> "Too many sign-in attempts. Wait and try again."
                is NotFound -> "No playable audio tracks."
                is ServerTooOld -> "This server is older than 2.26.0 and is not supported."
                is InvalidUrl -> "Enter a valid server URL."
                else -> "Something went wrong talking to the server."
            }
        }
    }
}

class TlsHandshakeException(
    message: String = "tls validation failed",
    cause: Throwable? = null,
) : Exception(message, cause)
