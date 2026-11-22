package io.github.miniaudiobookshelf.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlaybackQueueTest {
    @Test
    fun appendAndDedupe() {
        val queue = PlaybackQueue()
        assertEquals(QueueAppendResult.ADDED, queue.append(entry("a")))
        assertEquals(QueueAppendResult.DUPLICATE, queue.append(entry("a")))
        assertEquals(QueueAppendResult.ADDED, queue.append(entry("b")))
        assertEquals(2, queue.size)
        assertTrue(queue.contains("show", "a"))
        assertTrue(queue.contains("show", "b"))
        assertFalse(queue.contains("show", "c"))
    }

    @Test
    fun rejectCurrentlyPlaying() {
        val queue = PlaybackQueue()
        assertEquals(
            QueueAppendResult.CURRENT,
            queue.append(entry("now"), currentItemId = "show", currentEpisodeId = "now"),
        )
        assertEquals(0, queue.size)
        assertEquals(
            QueueAppendResult.ADDED,
            queue.append(entry("later"), currentItemId = "show", currentEpisodeId = "now"),
        )
    }

    @Test
    fun emptyEpisodeIsIgnored() {
        val queue = PlaybackQueue()
        assertEquals(
            QueueAppendResult.IGNORED,
            queue.append(QueueEntry(itemId = "show", episodeId = "", title = "x", showTitle = "Show")),
        )
    }

    @Test
    fun fifoPop() {
        val queue = PlaybackQueue()
        queue.append(entry("a"))
        queue.append(entry("b"))
        queue.append(entry("c"))
        assertEquals("a", queue.peek()?.episodeId)
        assertEquals("a", queue.popFront()?.episodeId)
        assertEquals("b", queue.popFront()?.episodeId)
        assertEquals(1, queue.size)
        assertTrue(queue.contains("show", "c"))
    }

    @Test
    fun takeAtLeavesSiblings() {
        val queue = PlaybackQueue()
        queue.append(entry("a"))
        queue.append(entry("b"))
        queue.append(entry("c"))
        queue.append(entry("d"))
        assertEquals("c", queue.takeAt(2)?.episodeId)
        val snap = queue.snapshot()
        assertEquals(listOf("a", "b", "d"), snap.map { it.episodeId })
    }

    @Test
    fun removeAtAndRemoveEpisode() {
        val queue = PlaybackQueue()
        queue.append(entry("a"))
        queue.append(entry("b"))
        queue.append(entry("c"))
        assertTrue(queue.removeAt(1))
        assertFalse(queue.contains("show", "b"))
        assertTrue(queue.removeEpisode("show", "c"))
        assertFalse(queue.removeEpisode("show", "missing"))
        assertFalse(queue.removeAt(4))
        assertEquals(1, queue.size)
    }

    @Test
    fun moveAndEndBounds() {
        val queue = PlaybackQueue()
        queue.append(entry("a"))
        queue.append(entry("b"))
        queue.append(entry("c"))
        assertTrue(queue.move(0, 2))
        assertEquals(listOf("b", "c", "a"), queue.snapshot().map { it.episodeId })
        assertTrue(queue.move(2, 0))
        assertEquals(listOf("a", "b", "c"), queue.snapshot().map { it.episodeId })
        assertFalse(queue.move(0, 0))
        assertFalse(queue.move(-1, 1))
        assertFalse(queue.move(0, 3))
    }

    @Test
    fun clearAndEmptyOps() {
        val queue = PlaybackQueue()
        queue.clear()
        assertNull(queue.popFront())
        assertNull(queue.takeAt(0))
        queue.append(entry("a"))
        queue.append(entry("b"))
        queue.clear()
        assertEquals(0, queue.size)
        assertFalse(queue.contains("show", "a"))
        assertTrue(queue.entries.value.isEmpty())
    }

    @Test
    fun codecRoundTrip() {
        val entries = listOf(entry("a"), entry("b"))
        assertEquals(entries, QueueCodec.decode(QueueCodec.encode(entries)))
        assertEquals(emptyList<QueueEntry>(), QueueCodec.decode(""))
        assertEquals(emptyList<QueueEntry>(), QueueCodec.decode("not json"))
        assertEquals(emptyList<QueueEntry>(), QueueCodec.decode("{}"))
    }

    @Test
    fun restoreDropsBlanksAndDuplicates() {
        val queue = PlaybackQueue()
        queue.restore(
            listOf(
                entry("a"),
                entry("a"),
                QueueEntry(itemId = "show", episodeId = ""),
                entry("b"),
            ),
        )
        assertEquals(2, queue.size)
        assertEquals(listOf("a", "b"), queue.snapshot().map { it.episodeId })
    }
}

private fun entry(id: String) = QueueEntry(
    itemId = "show",
    episodeId = id,
    title = "Episode $id",
    showTitle = "Show",
)
