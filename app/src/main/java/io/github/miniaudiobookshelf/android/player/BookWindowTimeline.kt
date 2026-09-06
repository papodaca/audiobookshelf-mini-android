package io.github.miniaudiobookshelf.android.player

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi

@UnstableApi
class BookWindowTimeline(
    private val mediaItem: MediaItem,
    private val durationUs: Long,
) : Timeline() {
    override fun getWindowCount(): Int = 1

    override fun getPeriodCount(): Int = 1

    override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window {
        window.set(
            WINDOW_UID,
            mediaItem,
            null,
            C.TIME_UNSET,
            C.TIME_UNSET,
            C.TIME_UNSET,
            true,
            false,
            null,
            0L,
            durationUs,
            0,
            0,
            0L,
        )
        return window
    }

    override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period {
        period.set(
            if (setIds) WINDOW_UID else null,
            if (setIds) WINDOW_UID else null,
            0,
            durationUs,
            0L,
        )
        return period
    }

    override fun getIndexOfPeriod(uid: Any): Int {
        return if (uid == WINDOW_UID) 0 else C.INDEX_UNSET
    }

    override fun getUidOfPeriod(periodIndex: Int): Any = WINDOW_UID

    companion object {
        private val WINDOW_UID = Any()
    }
}
