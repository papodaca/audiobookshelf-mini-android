package io.github.miniaudiobookshelf.android.player

import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.media3.common.PlaybackParameters
import androidx.media3.session.MediaController
import io.github.miniaudiobookshelf.android.MiniAbsApplication
import io.github.miniaudiobookshelf.core.LibraryItem
import io.github.miniaudiobookshelf.core.PlaybackStarter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class PlaybackCommands(
    private val app: MiniAbsApplication,
) : PlaybackStarter {
    internal val stateFlow = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = stateFlow

    @Volatile
    var engine: PlaybackEngine? = null
        private set

    @Volatile
    var controller: MediaController? = null

    var onLibraryInvalidated: (() -> Unit)? = null

    fun attach(engine: PlaybackEngine) {
        this.engine = engine
    }

    override fun notifyLibraryChanged() {
        onLibraryInvalidated?.invoke()
    }

    fun detach(engine: PlaybackEngine) {
        if (this.engine === engine) this.engine = null
    }

    override fun play(item: LibraryItem, episodeId: String?, startOver: Boolean) {
        startPlay(item.id, episodeId, if (startOver) 0.0 else -1.0)
    }

    override fun playAt(item: LibraryItem, episodeId: String?, bookTime: Double) {
        val current = stateFlow.value
        if (current.itemId == item.id && current.intended != IntendedState.STOPPED) {
            seekTo(bookTime)
        } else {
            startPlay(item.id, episodeId, bookTime)
        }
    }

    override fun seekTo(bookTime: Double) {
        val bound = controller
        if (bound != null) {
            bound.seekTo((bookTime * 1000.0).toLong().coerceAtLeast(0L))
        } else {
            engine?.seekBookTime(bookTime)
        }
    }

    override fun skip(deltaSeconds: Double) {
        val bound = controller
        if (bound != null) {
            if (deltaSeconds < 0) bound.seekBack() else bound.seekForward()
        } else {
            engine?.skip(deltaSeconds)
        }
    }

    override fun playPause() {
        val bound = controller
        if (bound != null) {
            if (stateFlow.value.intended == IntendedState.PLAYING) bound.pause() else bound.play()
        } else if (stateFlow.value.intended == IntendedState.PLAYING) {
            engine?.pause()
        } else {
            engine?.resume()
        }
    }

    override fun previousChapter() {
        val bound = controller
        if (bound != null) bound.seekToPrevious() else engine?.previousChapter()
    }

    override fun nextChapter() {
        val bound = controller
        if (bound != null) bound.seekToNext() else engine?.nextChapter()
    }

    override fun setRate(rate: Double) {
        val bound = controller
        if (bound != null) {
            bound.setPlaybackParameters(PlaybackParameters(rate.toFloat()))
        } else {
            engine?.setRate(rate)
        }
    }

    override fun setSleepTimerMinutes(minutes: Int) {
        engine?.setSleepTimerMinutes(minutes)
    }

    override fun setSleepTimerEndOfChapter() {
        engine?.setSleepTimerEndOfChapter()
    }

    override fun clearSleepTimer() {
        engine?.clearSleepTimer()
    }

    override fun retry() {
        engine?.retry()
    }

    override fun stop() {
        val intent = Intent(app, PlaybackService::class.java).setAction(PlaybackService.ACTION_STOP)
        app.startService(intent)
    }

    fun playItemId(itemId: String, episodeId: String? = null, startAt: Double = -1.0) {
        startPlay(itemId, episodeId, startAt)
    }

    override fun playingItemId(): String = stateFlow.value.itemId

    override fun playingEpisodeId(): String = stateFlow.value.episodeId

    fun resolveConflict(keepLocal: Boolean) {
        engine?.resolveConflict(keepLocal)
    }

    fun acknowledgeFinished() {
        engine?.acknowledgeFinished()
    }

    fun onSyncConflict(local: Double, server: Double) {
        if (app.foregroundActivities > 0) {
            engine?.raiseConflict(local, server)
        } else {
            engine?.resume()
        }
    }

    private fun startPlay(itemId: String, episodeId: String?, startAt: Double) {
        val intent = Intent(app, PlaybackService::class.java).apply {
            action = PlaybackService.ACTION_PLAY
            putExtra(PlaybackService.EXTRA_ITEM_ID, itemId)
            putExtra(PlaybackService.EXTRA_EPISODE_ID, episodeId)
            putExtra(PlaybackService.EXTRA_START_AT, startAt)
        }
        ContextCompat.startForegroundService(app, intent)
    }
}
