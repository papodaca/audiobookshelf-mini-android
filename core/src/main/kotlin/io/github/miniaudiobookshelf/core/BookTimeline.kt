package io.github.miniaudiobookshelf.core

data class TrackPosition(
    val index: Int,
    val localOffset: Double,
)

class BookTimeline(
    tracks: List<AudioTrack>,
    chapters: List<Chapter> = emptyList(),
) {
    private val tracks: List<AudioTrack> = tracks.toList()
    private val chapters: List<Chapter> = chapters.toList()

    val totalDuration: Double = if (this.tracks.isEmpty()) {
        0.0
    } else {
        val last = this.tracks.last()
        last.startOffset + last.duration
    }

    val hasChapters: Boolean get() = chapters.isNotEmpty()

    fun chapterList(): List<Chapter> = chapters

    fun resolve(bookTime: Double): TrackPosition {
        val t = clampBookTime(bookTime)
        if (tracks.isEmpty()) {
            return TrackPosition(index = 0, localOffset = 0.0)
        }
        for (i in tracks.indices) {
            val track = tracks[i]
            val end = track.startOffset + track.duration
            val last = i == tracks.lastIndex
            if (t < end || last) {
                var local = t - track.startOffset
                if (local < 0) local = 0.0
                if (local > track.duration) local = track.duration
                return TrackPosition(index = track.index, localOffset = local)
            }
        }
        val lastTrack = tracks.last()
        return TrackPosition(index = lastTrack.index, localOffset = lastTrack.duration)
    }

    fun bookTime(trackIndex: Int, localOffset: Double): Double {
        for (track in tracks) {
            if (track.index == trackIndex) {
                var local = localOffset
                if (local < 0) local = 0.0
                if (local > track.duration) local = track.duration
                return clampBookTime(track.startOffset + local)
            }
        }
        return 0.0
    }

    fun chapterAt(bookTime: Double): Chapter? {
        if (chapters.isEmpty()) return null
        val t = clampBookTime(bookTime)
        for (i in chapters.indices) {
            val chapter = chapters[i]
            val last = i == chapters.lastIndex
            if (t < chapter.end || last) return chapter
        }
        return chapters.last()
    }

    fun previousChapterTarget(bookTime: Double): Double {
        val t = clampBookTime(bookTime)
        val current = chapterAt(t) ?: return 0.0
        val intoChapter = t - current.start
        if (intoChapter > PREVIOUS_CHAPTER_RESTART_SECONDS) {
            return current.start
        }
        val index = chapters.indexOf(current)
        if (index <= 0) return 0.0
        return chapters[index - 1].start
    }

    fun nextChapterTarget(bookTime: Double): Double {
        val t = clampBookTime(bookTime)
        val current = chapterAt(t) ?: return totalDuration
        val index = chapters.indexOf(current)
        if (index < 0 || index >= chapters.lastIndex) return totalDuration
        return chapters[index + 1].start
    }

    private fun clampBookTime(bookTime: Double): Double {
        if (bookTime < 0) return 0.0
        if (bookTime > totalDuration) return totalDuration
        return bookTime
    }

    companion object {
        const val PREVIOUS_CHAPTER_RESTART_SECONDS = 3.0
    }
}
