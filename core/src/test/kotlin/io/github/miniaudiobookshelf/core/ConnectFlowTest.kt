package io.github.miniaudiobookshelf.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConnectFlowTest {
    private val server = ServerUrl.parse("https://abs.example.com")
    private val offerBoth = AuthOffer(password = true, apiKey = true)
    private val credentials = ConnectStep.Credentials(
        server = server,
        version = "2.30.0",
        offer = offerBoth,
        mode = AuthMode.PASSWORD,
        username = "alice",
        typedUrl = "https://abs.example.com",
    )

    @Test
    fun unauthorizedKeepsCredentialsAndUsername() {
        val next = ConnectFlow.reduce(credentials, ConnectEvent.SignInFailed(ConnectFailure.BAD_PASSWORD))
        val step = next as ConnectStep.Credentials
        assertEquals("alice", step.username)
        assertEquals(AuthMode.PASSWORD, step.mode)
        assertEquals(ViewLogic.userMessage(ConnectFailure.BAD_PASSWORD), step.error)
        assertFalse(step.busy)
        assertFalse(step.rateLimited)
    }

    @Test
    fun rejectedApiKeyKeepsCredentials() {
        val next = ConnectFlow.reduce(
            credentials.copy(mode = AuthMode.API_KEY),
            ConnectEvent.SignInFailed(ConnectFailure.REJECTED_API_KEY),
        )
        val step = next as ConnectStep.Credentials
        assertEquals("alice", step.username)
        assertEquals(ViewLogic.userMessage(ConnectFailure.REJECTED_API_KEY), step.error)
    }

    @Test
    fun rateLimitedKeepsCredentialsAndFlags() {
        val next = ConnectFlow.reduce(credentials, ConnectEvent.SignInFailed(ConnectFailure.RATE_LIMITED))
        val step = next as ConnectStep.Credentials
        assertTrue(step.rateLimited)
        assertEquals(ViewLogic.userMessage(ConnectFailure.RATE_LIMITED), step.error)
        assertFalse(step.busy)
    }

    @Test
    fun tlsDuringSignInGoesToServer() {
        val next = ConnectFlow.reduce(credentials, ConnectEvent.SignInFailed(ConnectFailure.TLS_UNTRUSTED))
        val step = next as ConnectStep.Server
        assertEquals("https://abs.example.com", step.url)
        assertEquals(ViewLogic.userMessage(ConnectFailure.TLS_UNTRUSTED), step.error)
        assertFalse(step.busy)
    }

    @Test
    fun unreachableDuringSignInGoesToServer() {
        val next = ConnectFlow.reduce(credentials, ConnectEvent.SignInFailed(ConnectFailure.UNREACHABLE))
        assertTrue(next is ConnectStep.Server)
        assertEquals("https://abs.example.com", (next as ConnectStep.Server).url)
    }

    @Test
    fun tooOldDuringSignInGoesToServer() {
        val next = ConnectFlow.reduce(credentials, ConnectEvent.SignInFailed(ConnectFailure.TOO_OLD))
        assertTrue(next is ConnectStep.Server)
    }

    @Test
    fun switchModeKeepsUsername() {
        val next = ConnectFlow.reduce(credentials, ConnectEvent.SwitchMode(AuthMode.API_KEY))
        val step = next as ConnectStep.Credentials
        assertEquals(AuthMode.API_KEY, step.mode)
        assertEquals("alice", step.username)
        assertEquals(server.origin, step.server.origin)
    }

    @Test
    fun changeServerReturnsTypedUrl() {
        val next = ConnectFlow.reduce(
            credentials.copy(typedUrl = "abs.lan:13378"),
            ConnectEvent.ChangeServer,
        )
        val step = next as ConnectStep.Server
        assertEquals("abs.lan:13378", step.url)
        assertFalse(step.busy)
        assertNull(step.error)
    }

    @Test
    fun probeOkLandsOnCredentials() {
        val next = ConnectFlow.reduce(
            ConnectStep.Server(url = "abs.example.com", busy = true),
            ConnectEvent.ProbeOk(
                server = server,
                version = "2.30.0",
                offer = offerBoth,
                mode = AuthMode.PASSWORD,
                username = "alice",
                reason = ConnectReason.SESSION_EXPIRED,
                typedUrl = "abs.example.com",
            ),
        )
        val step = next as ConnectStep.Credentials
        assertEquals("https://abs.example.com", step.server.origin)
        assertEquals("2.30.0", step.version)
        assertEquals("alice", step.username)
        assertEquals(ConnectReason.SESSION_EXPIRED, step.reason)
        assertEquals("abs.example.com", step.typedUrl)
        assertFalse(step.busy)
    }

    @Test
    fun probeFailedStaysOnServer() {
        val next = ConnectFlow.reduce(
            ConnectStep.Server(url = "https://abs.example.com", busy = true),
            ConnectEvent.ProbeFailed(
                url = "https://abs.example.com",
                message = ViewLogic.userMessage(ConnectFailure.UNREACHABLE),
            ),
        )
        val step = next as ConnectStep.Server
        assertEquals(ViewLogic.userMessage(ConnectFailure.UNREACHABLE), step.error)
        assertFalse(step.busy)
    }

    @Test
    fun httpNeedsAckShowsWarningOnTypedUrl() {
        val next = ConnectFlow.reduce(
            ConnectStep.Server(url = "abs.lan:13378", busy = true),
            ConnectEvent.HttpNeedsAck("abs.lan:13378"),
        )
        val step = next as ConnectStep.Server
        assertEquals("abs.lan:13378", step.url)
        assertTrue(step.httpWarning)
        assertFalse(step.httpAck)
        assertFalse(step.busy)
    }

    @Test
    fun tlsUntrustedWrapsServer() {
        val serverStep = ConnectStep.Server(url = "https://abs.lan", busy = true)
        val pin = PinPrompt(origin = "https://abs.lan", fingerprint = "abc", pem = "PEM")
        val next = ConnectFlow.reduce(serverStep, ConnectEvent.TlsUntrusted(pin))
        val trust = next as ConnectStep.Trust
        assertEquals("https://abs.lan", trust.server.url)
        assertFalse(trust.server.busy)
        assertEquals("abc", trust.pin.fingerprint)
    }

    @Test
    fun pinAcceptedReprobesInterruptedServer() {
        val trust = ConnectStep.Trust(
            server = ConnectStep.Server(url = "https://abs.lan", httpAck = true),
            pin = PinPrompt("https://abs.lan", "abc", "PEM"),
        )
        val next = ConnectFlow.reduce(trust, ConnectEvent.PinAccepted) as ConnectStep.Server
        assertEquals("https://abs.lan", next.url)
        assertTrue(next.busy)
        assertTrue(next.httpAck)
        assertNull(next.error)
    }

    @Test
    fun pinRefusedReturnsTlsError() {
        val trust = ConnectStep.Trust(
            server = ConnectStep.Server(url = "https://abs.lan"),
            pin = PinPrompt("https://abs.lan", "abc", "PEM"),
        )
        val next = ConnectFlow.reduce(trust, ConnectEvent.PinRefused) as ConnectStep.Server
        assertEquals(ViewLogic.userMessage(ConnectFailure.TLS_UNTRUSTED), next.error)
        assertFalse(next.busy)
    }
}
