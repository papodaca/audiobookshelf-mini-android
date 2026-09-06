package io.github.miniaudiobookshelf.core

enum class AuthMode {
    PASSWORD,
    API_KEY,
}

enum class ConnectReason {
    NONE,
    SIGNED_OUT,
    SESSION_EXPIRED,
    RESTORE_FAILED,
}

data class PinPrompt(
    val origin: String,
    val fingerprint: String,
    val pem: String,
)

sealed class ConnectStep {
    data class Server(
        val url: String = "",
        val busy: Boolean = false,
        val error: String? = null,
        val httpWarning: Boolean = false,
        val httpAck: Boolean = false,
    ) : ConnectStep()

    data class Trust(
        val server: Server,
        val pin: PinPrompt,
    ) : ConnectStep()

    data class Credentials(
        val server: ServerUrl,
        val version: String,
        val offer: AuthOffer,
        val mode: AuthMode,
        val username: String = "",
        val reason: ConnectReason = ConnectReason.NONE,
        val busy: Boolean = false,
        val error: String? = null,
        val rateLimited: Boolean = false,
        val typedUrl: String = "",
    ) : ConnectStep()
}

sealed class ConnectEvent {
    data class ProbeStarted(val url: String) : ConnectEvent()
    data class HttpNeedsAck(val url: String) : ConnectEvent()
    data class ProbeOk(
        val server: ServerUrl,
        val version: String,
        val offer: AuthOffer,
        val mode: AuthMode,
        val username: String,
        val reason: ConnectReason = ConnectReason.NONE,
        val typedUrl: String,
    ) : ConnectEvent()
    data class ProbeFailed(
        val url: String,
        val message: String,
        val httpWarning: Boolean = false,
        val httpAck: Boolean = false,
    ) : ConnectEvent()
    data class TlsUntrusted(val pin: PinPrompt) : ConnectEvent()
    data object PinAccepted : ConnectEvent()
    data object PinRefused : ConnectEvent()
    data object SignInStarted : ConnectEvent()
    data class SignInFailed(val failure: ConnectFailure) : ConnectEvent()
    data class SwitchMode(val mode: AuthMode) : ConnectEvent()
    data object ChangeServer : ConnectEvent()
}

object ConnectFlow {
    fun reduce(step: ConnectStep, event: ConnectEvent): ConnectStep {
        return when (event) {
            is ConnectEvent.ProbeStarted -> when (step) {
                is ConnectStep.Server -> step.copy(url = event.url, busy = true, error = null)
                is ConnectStep.Trust -> step.server.copy(url = event.url, busy = true, error = null)
                is ConnectStep.Credentials -> ConnectStep.Server(url = event.url, busy = true)
            }
            is ConnectEvent.HttpNeedsAck -> ConnectStep.Server(
                url = event.url,
                httpWarning = true,
                httpAck = false,
                busy = false,
            )
            is ConnectEvent.ProbeOk -> ConnectStep.Credentials(
                server = event.server,
                version = event.version,
                offer = event.offer,
                mode = event.mode,
                username = event.username,
                reason = event.reason,
                typedUrl = event.typedUrl.ifEmpty { event.server.normalized },
            )
            is ConnectEvent.ProbeFailed -> ConnectStep.Server(
                url = event.url,
                busy = false,
                error = event.message,
                httpWarning = event.httpWarning,
                httpAck = event.httpAck,
            )
            is ConnectEvent.TlsUntrusted -> ConnectStep.Trust(
                server = interruptedServer(step).copy(busy = false, error = null),
                pin = event.pin,
            )
            ConnectEvent.PinAccepted -> {
                val server = (step as? ConnectStep.Trust)?.server ?: return step
                server.copy(busy = true, error = null)
            }
            ConnectEvent.PinRefused -> {
                val server = (step as? ConnectStep.Trust)?.server ?: return step
                server.copy(
                    busy = false,
                    error = ViewLogic.userMessage(ConnectFailure.TLS_UNTRUSTED),
                )
            }
            ConnectEvent.SignInStarted -> (step as? ConnectStep.Credentials)?.copy(
                busy = true,
                error = null,
            ) ?: step
            is ConnectEvent.SignInFailed -> signInFailed(step, event.failure)
            is ConnectEvent.SwitchMode -> (step as? ConnectStep.Credentials)?.copy(
                mode = event.mode,
            ) ?: step
            ConnectEvent.ChangeServer -> {
                val creds = step as? ConnectStep.Credentials ?: return step
                ConnectStep.Server(url = creds.typedUrl.ifEmpty { creds.server.normalized })
            }
        }
    }

    private fun interruptedServer(step: ConnectStep): ConnectStep.Server {
        return when (step) {
            is ConnectStep.Server -> step
            is ConnectStep.Trust -> step.server
            is ConnectStep.Credentials -> ConnectStep.Server(
                url = step.typedUrl.ifEmpty { step.server.normalized },
            )
        }
    }

    private fun signInFailed(step: ConnectStep, failure: ConnectFailure): ConnectStep {
        val creds = step as? ConnectStep.Credentials
        if (creds != null && ViewLogic.signInFailureStays(failure)) {
            return creds.copy(
                busy = false,
                error = ViewLogic.userMessage(failure),
                rateLimited = failure == ConnectFailure.RATE_LIMITED,
            )
        }
        val url = when (step) {
            is ConnectStep.Credentials -> step.typedUrl.ifEmpty { step.server.normalized }
            is ConnectStep.Server -> step.url
            is ConnectStep.Trust -> step.server.url
        }
        return ConnectStep.Server(
            url = url,
            busy = false,
            error = ViewLogic.userMessage(failure),
        )
    }
}
