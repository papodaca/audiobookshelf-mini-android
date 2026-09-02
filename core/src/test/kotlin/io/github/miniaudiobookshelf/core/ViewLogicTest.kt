package io.github.miniaudiobookshelf.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
