package io.github.miniaudiobookshelf.core

/**
 * Sleep timer policy: pure deadline / end-of-chapter maths for the player.
 *
 * Two modes, exactly one active at a time:
 *  - minutes: pause once the wall-clock deadline passes.
 *  - end of chapter: pause once the book position reaches the end of the
 *    chapter that was playing when the timer was set (or the end of the
 *    book when already in the last chapter).
 *
 * Documented behaviour: any pause cancels the timer. The engine clears the
 * policy whenever playback pauses, so a timer never fires unexpectedly
 * after the user resumes.
 */
class SleepTimerPolicy {
    private var deadlineMs: Long? = null
    private var pauseAtBookTime: Double? = null

    val isActive: Boolean get() = deadlineMs != null || pauseAtBookTime != null

    val isEndOfChapter: Boolean get() = pauseAtBookTime != null

    fun setMinutes(minutes: Int, nowMs: Long) {
        require(minutes > 0) { "sleep minutes must be positive" }
        deadlineMs = nowMs + minutes * 60_000L
        pauseAtBookTime = null
    }

    fun setEndOfChapter(timeline: BookTimeline, bookTime: Double) {
        deadlineMs = null
        pauseAtBookTime = timeline.chapterAt(bookTime)?.end ?: timeline.totalDuration
    }

    fun clear() {
        deadlineMs = null
        pauseAtBookTime = null
    }

    fun shouldPause(nowMs: Long, bookTime: Double): Boolean {
        deadlineMs?.let { return nowMs >= it }
        pauseAtBookTime?.let { return bookTime >= it }
        return false
    }

    /** Milliseconds left in minutes mode, or null for end-of-chapter / off. */
    fun remainingMs(nowMs: Long): Long? = deadlineMs?.let { (it - nowMs).coerceAtLeast(0L) }

    /** Book position at which playback pauses in end-of-chapter mode. */
    fun pauseTarget(): Double? = pauseAtBookTime

    companion object {
        val PRESET_MINUTES: List<Int> = listOf(5, 10, 15, 30, 45, 60)
    }
}
