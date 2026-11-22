package io.github.miniaudiobookshelf.android.player

import io.github.miniaudiobookshelf.core.BookTimeline
import io.github.miniaudiobookshelf.core.Chapter

enum class IntendedState {
    STOPPED,
    PLAYING,
    PAUSED,
}

enum class PipelineState {
    STOPPED,
    BUFFERING,
    PLAYING,
    PAUSED,
    ERROR,
}

data class PlaybackConflict(
    val localPosition: Double,
    val serverPosition: Double,
)

data class PlayerState(
    val itemId: String = "",
    val timeline: BookTimeline? = null,
    val position: Double = 0.0,
    val duration: Double = 0.0,
    val rate: Double = 1.0,
    val chapter: Chapter? = null,
    val intended: IntendedState = IntendedState.STOPPED,
    val pipeline: PipelineState = PipelineState.STOPPED,
    val currentTrackIndex: Int = 0,
    val positionSuppressed: Boolean = false,
    val statusMessage: String = "",
    val retryAvailable: Boolean = false,
    val syncUnhealthy: Boolean = false,
    val seekGeneration: Int = 0,
    val title: String = "",
    val author: String = "",
    val artUrl: String = "",
    val episodeId: String = "",
    val stalled: Boolean = false,
    val conflict: PlaybackConflict? = null,
    val finished: Boolean = false,
    val sleepTimerActive: Boolean = false,
    val sleepEndOfChapter: Boolean = false,
    val sleepRemainingSeconds: Long? = null,
) {
    val hasChapters: Boolean get() = timeline?.hasChapters == true

    fun publishPosition(bookTime: Double): PlayerState {
        if (positionSuppressed || timeline == null) return this
        return copy(position = bookTime, chapter = timeline.chapterAt(bookTime))
    }
}
