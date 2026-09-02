package io.github.miniaudiobookshelf.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BookTimelineTest {
    @Test
    fun singleTrackPositions() {
        val timeline = BookTimeline(singleTrack())
        val start = timeline.resolve(0.0)
        assertEquals(1, start.index)
        assertEquals(0.0, start.localOffset, 0.0001)
        val mid = timeline.resolve(1800.0)
        assertEquals(1, mid.index)
        assertEquals(1800.0, mid.localOffset, 0.0001)
        val end = timeline.resolve(3600.0)
        assertEquals(1, end.index)
        assertEquals(3600.0, end.localOffset, 0.0001)
    }

    @Test
    fun multiTrackInsideThird() {
        val pos = BookTimeline(multiTrack()).resolve(350.0)
        assertEquals(3, pos.index)
        assertEquals(50.0, pos.localOffset, 0.0001)
    }

    @Test
    fun boundaryResolvesToLaterTrack() {
        val pos = BookTimeline(multiTrack()).resolve(300.0)
        assertEquals(3, pos.index)
        assertEquals(0.0, pos.localOffset, 0.0001)
    }

    @Test
    fun clampPastEndAndNegative() {
        val timeline = BookTimeline(multiTrack())
        assertEquals(600.0, timeline.totalDuration, 0.0001)
        val past = timeline.resolve(9999.0)
        assertEquals(3, past.index)
        assertEquals(300.0, past.localOffset, 0.0001)
        val neg = timeline.resolve(-12.0)
        assertEquals(1, neg.index)
        assertEquals(0.0, neg.localOffset, 0.0001)
    }

    @Test
    fun roundTripEveryTrack() {
        val timeline = BookTimeline(multiTrack())
        val samples = listOf(0.0, 50.0, 99.9, 100.0, 200.0, 299.9, 300.0, 450.0, 600.0)
        for (t in samples) {
            val pos = timeline.resolve(t)
            val back = timeline.bookTime(pos.index, pos.localOffset)
            assertEquals(t.coerceIn(0.0, timeline.totalDuration), back, 0.0001)
        }
    }

    @Test
    fun chapterLookup() {
        val timeline = BookTimeline(singleTrack(), twoChapters())
        assertEquals("Two", timeline.chapterAt(1800.0)?.title)
        assertEquals("One", timeline.chapterAt(100.0)?.title)
    }

    @Test
    fun chapterListPreservesOrder() {
        val timeline = BookTimeline(singleTrack(), twoChapters())
        val chapters = timeline.chapterList()
        assertEquals(2, chapters.size)
        assertEquals("One", chapters[0].title)
        assertEquals("Two", chapters[1].title)
        assertEquals(0, BookTimeline(singleTrack()).chapterList().size)
    }

    @Test
    fun chapterMidTrackIndependent() {
        val tracks = listOf(track(1, 0.0, 3600.0))
        val chapters = listOf(
            chapter(0, 0.0, 900.0, "Intro"),
            chapter(1, 900.0, 3600.0, "Body"),
        )
        val timeline = BookTimeline(tracks, chapters)
        assertEquals(1, timeline.resolve(900.0).index)
        assertEquals("Body", timeline.chapterAt(900.0)?.title)
    }

    @Test
    fun previousChapterThreshold() {
        val timeline = BookTimeline(singleTrack(), twoChapters())
        assertEquals(0.0, timeline.previousChapterTarget(1801.0), 0.0001)
        assertEquals(1800.0, timeline.previousChapterTarget(1830.0), 0.0001)
    }

    @Test
    fun nextFromFinalChapterClamps() {
        val timeline = BookTimeline(singleTrack(), twoChapters())
        assertEquals(3600.0, timeline.nextChapterTarget(2000.0), 0.0001)
    }

    @Test
    fun noChaptersStillMapsTracks() {
        val timeline = BookTimeline(multiTrack())
        assertFalse(timeline.hasChapters)
        assertNull(timeline.chapterAt(50.0))
        assertEquals(2, timeline.resolve(150.0).index)
        assertEquals(600.0, timeline.totalDuration, 0.0001)
    }

    @Test
    fun singleChapterPrevNext() {
        val timeline = BookTimeline(singleTrack(), listOf(chapter(0, 0.0, 3600.0, "Whole")))
        assertEquals(0.0, timeline.previousChapterTarget(1.0), 0.0001)
        assertEquals(0.0, timeline.previousChapterTarget(30.0), 0.0001)
        assertEquals(3600.0, timeline.nextChapterTarget(10.0), 0.0001)
        assertTrue(timeline.hasChapters)
    }
}

private fun track(index: Int, startOffset: Double, duration: Double) = AudioTrack(
    index = index,
    startOffset = startOffset,
    duration = duration,
)

private fun chapter(id: Int, start: Double, end: Double, title: String) = Chapter(
    id = id,
    start = start,
    end = end,
    title = title,
)

private fun singleTrack() = listOf(track(1, 0.0, 3600.0))

private fun multiTrack() = listOf(
    track(1, 0.0, 100.0),
    track(2, 100.0, 200.0),
    track(3, 300.0, 300.0),
)

private fun twoChapters() = listOf(
    chapter(0, 0.0, 1800.0, "One"),
    chapter(1, 1800.0, 3600.0, "Two"),
)
