package io.github.miniaudiobookshelf.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ViewLogicTest {
    @Test
    fun localAuthOffersPasswordAndApiKey() {
        val offer = ViewLogic.authOffer(ServerStatus(version = "2.30.0", authMethods = listOf("local")))
        assertTrue(offer.password)
        assertTrue(offer.apiKey)
    }

    @Test
    fun ssoOnlyExcludesPassword() {
        val offer = ViewLogic.authOffer(ServerStatus(version = "2.30.0", authMethods = listOf("openid")))
        assertFalse(offer.password)
        assertTrue(offer.apiKey)
    }

    @Test
    fun oldServerIsRefused() {
        assertEquals(ConnectDecision.TOO_OLD, ViewLogic.versionDecision("2.25.9"))
        assertEquals(ConnectDecision.PROCEED, ViewLogic.versionDecision("2.26.0"))
    }

    @Test
    fun failureIdsAreDistinct() {
        val ids = ConnectFailure.entries.map { ViewLogic.messageId(it) }.toSet()
        assertEquals(ConnectFailure.entries.size, ids.size)
        ConnectFailure.entries.forEach { failure ->
            assertTrue(ViewLogic.userMessage(failure).isNotEmpty())
        }
    }

    @Test
    fun httpWarningCoversConfidentialityAndIntegrity() {
        val text = ViewLogic.userMessage(ConnectFailure.HTTP_WARNING).lowercase()
        assertTrue(text.contains("unencrypted") || text.contains("clear"))
        assertTrue(text.contains("audio") || text.contains("altered"))
    }

    @Test
    fun nextSpeedWalksPresetsThenWraps() {
        assertEquals(1.25, ViewLogic.nextSpeed(1.0))
        assertEquals(0.5, ViewLogic.nextSpeed(3.0))
        assertEquals(1.0, ViewLogic.nextSpeed(0.9))
    }

    @Test
    fun controllerZeroMeansResumeNotRestart() {
        assertEquals(-1.0, ViewLogic.controllerStartOverride(0L))
        assertEquals(-1.0, ViewLogic.controllerStartOverride(-1L))
        assertEquals(144.0, ViewLogic.controllerStartOverride(144_000L))
        assertTrue(ViewLogic.ignoreControllerResetSeek(120.0, 0.0))
        assertFalse(ViewLogic.ignoreControllerResetSeek(2.0, 0.0))
        assertFalse(ViewLogic.ignoreControllerResetSeek(120.0, 90.0))
    }

    @Test
    fun defaultModeIsPasswordWhenOfferedAndNothingRemembered() {
        val offer = AuthOffer(password = true, apiKey = true)
        assertEquals(AuthMode.PASSWORD, ViewLogic.defaultAuthMode(offer, remembered = null))
    }

    @Test
    fun defaultModeIsApiKeyWhenRemembered() {
        val offer = AuthOffer(password = true, apiKey = true)
        assertEquals(AuthMode.API_KEY, ViewLogic.defaultAuthMode(offer, AuthMode.API_KEY))
    }

    @Test
    fun defaultModeIsApiKeyWhenLocalIsAbsent() {
        val offer = AuthOffer(password = false, apiKey = true)
        assertEquals(AuthMode.API_KEY, ViewLogic.defaultAuthMode(offer, AuthMode.PASSWORD))
        assertEquals(AuthMode.API_KEY, ViewLogic.defaultAuthMode(offer, remembered = null))
    }

    @Test
    fun canSubmitMatrix() {
        assertFalse(ViewLogic.canSubmit(AuthMode.PASSWORD, "", "secret", ""))
        assertFalse(ViewLogic.canSubmit(AuthMode.PASSWORD, "alice", "", ""))
        assertFalse(ViewLogic.canSubmit(AuthMode.PASSWORD, "  ", "secret", ""))
        assertTrue(ViewLogic.canSubmit(AuthMode.PASSWORD, "alice", "secret", ""))
        assertFalse(ViewLogic.canSubmit(AuthMode.API_KEY, "alice", "secret", ""))
        assertFalse(ViewLogic.canSubmit(AuthMode.API_KEY, "", "", "  "))
        assertTrue(ViewLogic.canSubmit(AuthMode.API_KEY, "", "", "mabs_key"))
    }

    @Test
    fun signInFailureStaysForAuthRejects() {
        assertTrue(ViewLogic.signInFailureStays(ConnectFailure.BAD_PASSWORD))
        assertTrue(ViewLogic.signInFailureStays(ConnectFailure.REJECTED_API_KEY))
        assertTrue(ViewLogic.signInFailureStays(ConnectFailure.RATE_LIMITED))
        assertFalse(ViewLogic.signInFailureStays(ConnectFailure.TLS_UNTRUSTED))
        assertFalse(ViewLogic.signInFailureStays(ConnectFailure.UNREACHABLE))
        assertFalse(ViewLogic.signInFailureStays(ConnectFailure.TOO_OLD))
        assertFalse(ViewLogic.signInFailureStays(ConnectFailure.NOT_ABS))
    }

    @Test
    fun schemeLessGivesHttpsThenHttp() {
        val candidates = ViewLogic.probeCandidates("abs.lan:13378")
        assertEquals(listOf("https://abs.lan:13378", "http://abs.lan:13378"), candidates.map { it.normalized })
    }

    @Test
    fun typedHttpsIsSingleCandidate() {
        val candidates = ViewLogic.probeCandidates("https://abs.lan:13378")
        assertEquals(listOf("https://abs.lan:13378"), candidates.map { it.normalized })
    }

    @Test
    fun typedHttpIsSingleCandidate() {
        val candidates = ViewLogic.probeCandidates("http://abs.lan:13378")
        assertEquals(listOf("http://abs.lan:13378"), candidates.map { it.normalized })
    }

    @Test
    fun trailingSlashStrippedOnBothCandidates() {
        val schemeLess = ViewLogic.probeCandidates("abs.lan:13378/")
        assertEquals(listOf("https://abs.lan:13378", "http://abs.lan:13378"), schemeLess.map { it.normalized })
        val typed = ViewLogic.probeCandidates("https://abs.lan:13378/")
        assertEquals(listOf("https://abs.lan:13378"), typed.map { it.normalized })
    }
}

class SettingsStoreMemoryTest {
    @Test
    fun lastUsernameAndModeRoundTripPerOrigin() {
        val settings = MemorySettingsStore()
        val originA = "https://abs.example.com"
        val originB = "http://abs.lan:13378"
        assertEquals("", settings.getLastUsername(originA))
        assertNull(settings.getLastAuthMode(originA))
        settings.setLastUsername(originA, "alice")
        settings.setLastAuthMode(originA, AuthMode.PASSWORD)
        settings.setLastUsername(originB, "bob")
        settings.setLastAuthMode(originB, AuthMode.API_KEY)
        assertEquals("alice", settings.getLastUsername(originA))
        assertEquals(AuthMode.PASSWORD, settings.getLastAuthMode(originA))
        assertEquals("bob", settings.getLastUsername(originB))
        assertEquals(AuthMode.API_KEY, settings.getLastAuthMode(originB))
        settings.setLastUsername(originA, "carol")
        settings.setLastAuthMode(originA, AuthMode.API_KEY)
        assertEquals("carol", settings.getLastUsername(originA))
        assertEquals(AuthMode.API_KEY, settings.getLastAuthMode(originA))
        assertEquals("bob", settings.getLastUsername(originB))
    }
}

class DownloadTotalsTest {
    @Test
    fun formatBytesUnits() {
        assertEquals("512 B", ViewLogic.formatBytes(512))
        assertEquals("1.5 KB", ViewLogic.formatBytes(1536))
        assertEquals("2.0 MB", ViewLogic.formatBytes(2L * 1024 * 1024))
        assertEquals("1.50 GB", ViewLogic.formatBytes(1536L * 1024 * 1024))
    }

    @Test
    fun completedBytesCountsOnlyComplete() {
        val records = listOf(
            DownloadRecord(itemId = "a", status = DownloadStatus.COMPLETE, bytesDownloaded = 100, bytesTotal = 100),
            DownloadRecord(itemId = "b", status = DownloadStatus.RUNNING, bytesDownloaded = 50, bytesTotal = 200),
            DownloadRecord(itemId = "c", status = DownloadStatus.COMPLETE, bytesDownloaded = 300, bytesTotal = 300),
        )
        assertEquals(400L, ViewLogic.completedDownloadBytes(records))
        assertEquals(0L, ViewLogic.completedDownloadBytes(emptyList()))
    }
}

class TrustStoreTest {
    @Test
    fun pinMatchesExactPem() {
        val settings = MemorySettingsStore()
        val trust = TrustStore(settings)
        val pemA = "-----BEGIN CERTIFICATE-----\nAAA\n-----END CERTIFICATE-----\n"
        val pemB = "-----BEGIN CERTIFICATE-----\nBBB\n-----END CERTIFICATE-----\n"
        trust.pin("https://abs.example.com", pemA)
        assertTrue(trust.matches("https://abs.example.com", pemA))
        assertFalse(trust.matches("https://abs.example.com", pemB))
    }
}
