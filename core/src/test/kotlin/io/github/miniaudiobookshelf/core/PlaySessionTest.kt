package io.github.miniaudiobookshelf.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlaySessionTest {
    @Test
    fun parseFixture() {
        val json = PlaySessionTest::class.java.classLoader!!.getResource("fixtures/play-session.json")!!.readText()
        val session = PlaySession.parse(json)
        assertEquals("sess-abc-123", session.id)
        assertEquals("item-single", session.libraryItemId)
        assertEquals(120.5, session.currentTime, 0.0001)
        assertEquals(3600.0, session.duration, 0.0001)
        assertEquals(0, session.playMethod)
        assertTrue(session.isDirectPlay)
        assertEquals("The Single File Book", session.title)
        assertEquals("Ada Author", session.author)
        assertEquals(1, session.tracks.size)
        assertEquals(1, session.tracks[0].index)
        assertEquals(2, session.chapters.size)
        assertEquals("Chapter One", session.chapters[0].title)
    }

    @Test
    fun transcodeIsNotDirectPlay() {
        val json = """
            {
              "id": "sess-x",
              "libraryItemId": "item-x",
              "playMethod": 1,
              "currentTime": 0,
              "duration": 10,
              "audioTracks": [
                {"index": 1, "startOffset": 0, "duration": 10, "title": "out.m3u8"}
              ]
            }
        """.trimIndent()
        val session = PlaySession.parse(json)
        assertFalse(session.isDirectPlay)
    }

    @Test
    fun emptyTracksIsNotDirectPlay() {
        val json = """{"id":"sess-x","playMethod":0,"audioTracks":[]}"""
        assertFalse(PlaySession.parse(json).isDirectPlay)
    }
}
