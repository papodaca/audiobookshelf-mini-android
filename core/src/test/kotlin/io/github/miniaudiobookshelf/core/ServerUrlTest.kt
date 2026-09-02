package io.github.miniaudiobookshelf.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ServerUrlTest {
    @Test
    fun normalizesSchemeHostPort() {
        val a = ServerUrl.parse("HTTPS://ABS.Example.com:443/")
        val b = ServerUrl.parse("https://abs.example.com")
        val c = ServerUrl.parse("http://abs.example.com:80/library")
        assertEquals("https://abs.example.com", a.normalized)
        assertEquals(a.normalized, b.normalized)
        assertEquals("http://abs.example.com", c.normalized)
        assertTrue(a.isHttps)
        assertTrue(c.isHttp)
        assertFalse(a.schemeLess)
    }

    @Test
    fun customPortKept() {
        val url = ServerUrl.parse("https://abs.example.com:8443/")
        assertEquals("https://abs.example.com:8443", url.normalized)
    }

    @Test
    fun schemelessClassified() {
        val url = ServerUrl.parse("abs.example.com")
        assertTrue(url.schemeLess)
        assertFalse(url.isHttps)
        assertFalse(url.isHttp)
    }

    @Test
    fun emptyInputRejected() {
        assertThrows<ApiError.InvalidUrl> { ServerUrl.parse("") }
        assertThrows<ApiError.InvalidUrl> { ServerUrl.parse("   ") }
    }

    @Test
    fun stripsTrailingSlashes() {
        val url = ServerUrl.parse("https://abs.example.com///")
        assertEquals("https://abs.example.com", url.normalized)
    }
}
