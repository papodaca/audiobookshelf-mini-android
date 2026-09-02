package io.github.miniaudiobookshelf.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ServerVersionTest {
    @Test
    fun floorAndNeighbors() {
        assertTrue(ServerVersion.isSupported("2.26.0"))
        assertTrue(ServerVersion.isSupported("2.36.0"))
        assertFalse(ServerVersion.isSupported("2.25.9"))
    }

    @Test
    fun betaSuffixParsesFirstThreeNumericPartsLikeVala() {
        assertEquals(0, ServerVersion.compare("2.26.0-beta", "2.26.0"))
        assertTrue(ServerVersion.isSupported("2.26.0-beta"))
        assertFalse(ServerVersion.isSupported("2.25.9-beta"))
    }
}
