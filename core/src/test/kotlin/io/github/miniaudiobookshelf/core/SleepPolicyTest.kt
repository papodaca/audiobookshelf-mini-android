package io.github.miniaudiobookshelf.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SleepPolicyTest {
    private fun timeline(): BookTimeline = BookTimeline(
        tracks = listOf(AudioTrack(index = 0, duration = 250.0)),
        chapters = listOf(
            Chapter(id = 1, start = 0.0, end = 100.0, title = "One"),
            Chapter(id = 2, start = 100.0, end = 250.0, title = "Two"),
        ),
    )

    @Test
    fun minutesDeadlineFiresAtDeadline() {
        val policy = SleepTimerPolicy()
        policy.setMinutes(15, nowMs = 1_000L)
        assertTrue(policy.isActive)
        assertFalse(policy.shouldPause(nowMs = 1_000L + 14 * 60_000L, bookTime = 0.0))
        assertTrue(policy.shouldPause(nowMs = 1_000L + 15 * 60_000L, bookTime = 0.0))
        assertEquals(60_000L, policy.remainingMs(nowMs = 1_000L + 14 * 60_000L))
    }

    @Test
    fun endOfChapterTargetsCurrentChapterEnd() {
        val policy = SleepTimerPolicy()
        policy.setEndOfChapter(timeline(), bookTime = 40.0)
        assertTrue(policy.isEndOfChapter)
        assertEquals(100.0, policy.pauseTarget() ?: -1.0, 0.0001)
        assertFalse(policy.shouldPause(nowMs = 0L, bookTime = 99.0))
        assertTrue(policy.shouldPause(nowMs = 0L, bookTime = 100.0))
        assertNull(policy.remainingMs(nowMs = 0L))
    }

    @Test
    fun endOfChapterInLastChapterTargetsBookEnd() {
        val policy = SleepTimerPolicy()
        policy.setEndOfChapter(timeline(), bookTime = 240.0)
        assertEquals(250.0, policy.pauseTarget() ?: -1.0, 0.0001)
    }

    @Test
    fun settingOneModeClearsTheOther() {
        val policy = SleepTimerPolicy()
        policy.setMinutes(10, nowMs = 0L)
        policy.setEndOfChapter(timeline(), bookTime = 0.0)
        assertNull(policy.remainingMs(nowMs = 0L))
        policy.setMinutes(10, nowMs = 0L)
        assertNull(policy.pauseTarget())
        assertFalse(policy.isEndOfChapter)
    }

    @Test
    fun clearDeactivates() {
        val policy = SleepTimerPolicy()
        policy.setMinutes(10, nowMs = 0L)
        policy.clear()
        assertFalse(policy.isActive)
        assertFalse(policy.shouldPause(nowMs = Long.MAX_VALUE, bookTime = Double.MAX_VALUE))
    }
}
