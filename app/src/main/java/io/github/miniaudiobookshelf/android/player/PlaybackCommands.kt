package io.github.miniaudiobookshelf.android.player

import android.content.Intent
import androidx.core.content.ContextCompat
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

    fun attach(engine: PlaybackEngine) {
        this.engine = engine
    }

    var onLibraryInvalidated: (() -> Unit)? = null

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
            engine?.seekBookTime(bookTime) ?: startPlay(item.id, episodeId, bookTime)
        } else {
            startPlay(item.id, episodeId, bookTime)
        }
    }

    override fun seekTo(bookTime: Double) {
        engine?.seekBookTime(bookTime)
    }

    override fun skip(deltaSeconds: Double) {
        engine?.skip(deltaSeconds)
    }

    override fun stop() {
        val intent = Intent(app, PlaybackService::class.java).setAction(PlaybackService.ACTION_STOP)
        app.startService(intent)
    }

    fun playItemId(itemId: String, episodeId: String? = null, startAt: Double = -1.0) {
        startPlay(itemId, episodeId, startAt)
    }

    fun retry() {
        engine?.retry()
    }

    fun resolveConflict(keepLocal: Boolean) {
        engine?.resolveConflict(keepLocal)
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
