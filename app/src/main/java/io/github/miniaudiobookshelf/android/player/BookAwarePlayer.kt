package io.github.miniaudiobookshelf.android.player

import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import io.github.miniaudiobookshelf.core.AutoMediaId
import io.github.miniaudiobookshelf.core.ViewLogic

@UnstableApi
class BookAwarePlayer(
    private val exo: ExoPlayer,
) : ForwardingPlayer(exo) {
    var engine: PlaybackEngine? = null
    var onPlayItem: ((itemId: String, episodeId: String?, startAt: Double) -> Unit)? = null
    var onPlayFromSearch: ((query: String) -> Unit)? = null
    var allowResume: () -> Boolean = { true }

    private val extraListeners = LinkedHashSet<Player.Listener>()
    private var lastTimelineItemId: String = ""

    var artworkData: ByteArray? = null
        set(value) {
            field = value
            val metadata = mediaMetadata
            extraListeners.toList().forEach { it.onMediaMetadataChanged(metadata) }
        }

    override fun addListener(listener: Player.Listener) {
        super.addListener(listener)
        synchronized(extraListeners) { extraListeners.add(listener) }
    }

    override fun removeListener(listener: Player.Listener) {
        super.removeListener(listener)
        synchronized(extraListeners) { extraListeners.remove(listener) }
    }

    override fun seekBack() {
        engine?.skip(-30.0)
    }

    override fun seekForward() {
        engine?.skip(30.0)
    }

    override fun seekToPrevious() {
        engine?.previousChapter()
    }

    override fun seekToNext() {
        engine?.nextChapter()
    }

    override fun seekToPreviousMediaItem() {
        engine?.previousChapter()
    }

    override fun seekToNextMediaItem() {
        engine?.nextChapter()
    }

    override fun seekTo(positionMs: Long) {
        val current = engine?.state?.value?.position ?: 0.0
        val target = positionMs / 1000.0
        if (ViewLogic.ignoreControllerResetSeek(current, target)) {
            android.util.Log.i(
                PlaybackService.AUTO_TAG,
                "ignore seekTo($positionMs) at position=$current",
            )
            return
        }
        engine?.seekBookTime(target)
    }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        seekTo(positionMs)
    }

    override fun play() {
        if (!allowResume()) {
            android.util.Log.i(PlaybackService.AUTO_TAG, "play ignored after car disconnect")
            return
        }
        engine?.resume()
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady) {
            if (!allowResume()) {
                android.util.Log.i(PlaybackService.AUTO_TAG, "playWhenReady ignored after car disconnect")
                return
            }
            engine?.resume()
        } else {
            engine?.pause()
        }
    }

    override fun prepare() {
        if (exo.mediaItemCount > 0) super.prepare()
    }

    override fun pause() {
        engine?.pause()
    }

    override fun getPlayWhenReady(): Boolean {
        val intended = engine?.state?.value?.intended ?: return super.getPlayWhenReady()
        return intended == IntendedState.PLAYING
    }

    override fun getPlaybackState(): Int {
        val state = engine?.state?.value ?: return super.getPlaybackState()
        if (state.itemId.isEmpty()) return Player.STATE_IDLE
        return when (state.pipeline) {
            PipelineState.BUFFERING -> Player.STATE_BUFFERING
            PipelineState.PLAYING, PipelineState.PAUSED -> Player.STATE_READY
            PipelineState.ERROR -> Player.STATE_BUFFERING
            PipelineState.STOPPED -> if (state.intended == IntendedState.STOPPED) {
                Player.STATE_IDLE
            } else {
                Player.STATE_BUFFERING
            }
        }
    }

    override fun isPlaying(): Boolean {
        return playWhenReady && playbackState != Player.STATE_IDLE
    }

    override fun setPlaybackParameters(params: PlaybackParameters) {
        engine?.setRate(params.speed.toDouble())
    }

    override fun getPlaybackParameters(): PlaybackParameters {
        val rate = engine?.state?.value?.rate ?: return super.getPlaybackParameters()
        return PlaybackParameters(rate.toFloat())
    }

    override fun setMediaItem(mediaItem: MediaItem) {
        handleControllerItems(listOf(mediaItem), C.TIME_UNSET)
    }

    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) {
        handleControllerItems(listOf(mediaItem), startPositionMs)
    }

    override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) {
        handleControllerItems(listOf(mediaItem), C.TIME_UNSET)
    }

    override fun setMediaItems(mediaItems: List<MediaItem>) {
        handleControllerItems(mediaItems, C.TIME_UNSET)
    }

    override fun setMediaItems(mediaItems: List<MediaItem>, resetPosition: Boolean) {
        handleControllerItems(mediaItems, C.TIME_UNSET)
    }

    override fun setMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
        handleControllerItems(mediaItems, startPositionMs)
    }

    override fun addMediaItem(mediaItem: MediaItem) {
        handleControllerItems(listOf(mediaItem), C.TIME_UNSET)
    }

    override fun addMediaItems(mediaItems: List<MediaItem>) {
        handleControllerItems(mediaItems, C.TIME_UNSET)
    }

    override fun getDuration(): Long {
        val duration = engine?.state?.value?.duration ?: 0.0
        return if (duration > 0) (duration * 1000.0).toLong() else C.TIME_UNSET
    }

    override fun getCurrentPosition(): Long {
        val position = engine?.state?.value?.position ?: 0.0
        return (position * 1000.0).toLong().coerceAtLeast(0L)
    }

    override fun getContentDuration(): Long = duration

    override fun getContentPosition(): Long = currentPosition

    override fun getBufferedPosition(): Long {
        val state = engine?.state?.value ?: return currentPosition
        val timeline = state.timeline ?: return currentPosition
        val localBuffered = exo.bufferedPosition / 1000.0
        val book = timeline.bookTime(state.currentTrackIndex, localBuffered)
        return (book * 1000.0).toLong().coerceAtLeast(currentPosition)
    }

    override fun getBufferedPercentage(): Int {
        val total = duration
        if (total == C.TIME_UNSET || total <= 0L) return 0
        return ((bufferedPosition * 100) / total).toInt().coerceIn(0, 100)
    }

    override fun getCurrentMediaItemIndex(): Int = 0

    override fun getCurrentPeriodIndex(): Int = 0

    override fun getMediaItemCount(): Int = if (currentMediaItem == null) 0 else 1

    override fun getMediaItemAt(index: Int): MediaItem {
        val item = currentMediaItem
        if (index != 0 || item == null) {
            throw IndexOutOfBoundsException("index $index")
        }
        return item
    }

    override fun getCurrentMediaItem(): MediaItem? {
        val state = engine?.state?.value ?: return null
        if (state.itemId.isEmpty()) return null
        return buildMediaItem(state)
    }

    override fun getMediaMetadata(): MediaMetadata {
        return currentMediaItem?.mediaMetadata ?: MediaMetadata.EMPTY
    }

    override fun getCurrentTimeline(): Timeline {
        val item = currentMediaItem ?: return Timeline.EMPTY
        val durationMs = duration
        val durationUs = if (durationMs == C.TIME_UNSET) C.TIME_UNSET else durationMs * 1000
        return BookWindowTimeline(item, durationUs)
    }

    override fun hasNextMediaItem(): Boolean = engine?.state?.value?.hasChapters == true

    override fun hasPreviousMediaItem(): Boolean = engine?.state?.value?.hasChapters == true

    override fun isCurrentMediaItemSeekable(): Boolean = currentMediaItem != null

    override fun getAvailableCommands(): Player.Commands {
        val builder = super.getAvailableCommands()
            .buildUpon()
            .add(Player.COMMAND_SEEK_BACK)
            .add(Player.COMMAND_SEEK_FORWARD)
            .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            .add(Player.COMMAND_PLAY_PAUSE)
            .add(Player.COMMAND_SET_SPEED_AND_PITCH)
        val hasChapters = engine?.state?.value?.hasChapters == true
        if (hasChapters) {
            builder
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        } else {
            builder
                .remove(Player.COMMAND_SEEK_TO_NEXT)
                .remove(Player.COMMAND_SEEK_TO_PREVIOUS)
                .remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        }
        return builder.build()
    }

    fun notifySession() {
        val listeners = synchronized(extraListeners) { extraListeners.toList() }
        val metadata = mediaMetadata
        val commands = availableCommands
        val timeline = currentTimeline
        val ready = playWhenReady
        val playing = isPlaying
        val playback = playbackState
        val itemId = engine?.state?.value?.itemId.orEmpty()
        val timelineReason = if (itemId != lastTimelineItemId) {
            lastTimelineItemId = itemId
            Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED
        } else {
            Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE
        }
        listeners.forEach { listener ->
            listener.onMediaMetadataChanged(metadata)
            listener.onAvailableCommandsChanged(commands)
            listener.onTimelineChanged(timeline, timelineReason)
            listener.onPlayWhenReadyChanged(ready, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            listener.onPlaybackStateChanged(playback)
            listener.onIsPlayingChanged(playing)
        }
    }

    private fun handleControllerItems(items: List<MediaItem>, startPositionMs: Long) {
        if (items.isEmpty()) {
            engine?.stop()
            return
        }
        val first = items[0]
        val query = first.requestMetadata.searchQuery
        if (!query.isNullOrEmpty()) {
            onPlayFromSearch?.invoke(query)
            return
        }
        val parsed = AutoMediaId.parse(first.mediaId) as? AutoMediaId.Item ?: return
        val start = ViewLogic.controllerStartOverride(startPositionMs)
        val current = engine?.state?.value
        val sameItem = current != null &&
            current.itemId == parsed.itemId &&
            current.episodeId == parsed.episodeId.orEmpty() &&
            current.intended != IntendedState.STOPPED
        android.util.Log.i(
            PlaybackService.AUTO_TAG,
            "play mediaId=${first.mediaId} startMs=$startPositionMs override=$start same=$sameItem pos=${current?.position}",
        )
        if (!allowResume()) {
            android.util.Log.i(PlaybackService.AUTO_TAG, "controller play ignored after car disconnect")
            return
        }
        if (sameItem) {
            if (start >= 0) {
                engine?.seekBookTime(start)
            } else if (current.intended != IntendedState.PLAYING) {
                engine?.resume()
            }
            return
        }
        onPlayItem?.invoke(parsed.itemId, parsed.episodeId, start)
            ?: engine?.playItem(parsed.itemId, parsed.episodeId, start)
    }

    private fun buildMediaItem(state: PlayerState): MediaItem {
        val id = AutoMediaId.Item(state.itemId, state.episodeId.ifEmpty { null }).encode()
        val metadata = MediaMetadata.Builder()
            .setTitle(state.title)
            .setArtist(state.author)
            .setMediaType(
                if (state.episodeId.isNotEmpty()) {
                    MediaMetadata.MEDIA_TYPE_PODCAST
                } else {
                    MediaMetadata.MEDIA_TYPE_AUDIO_BOOK
                },
            )
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .apply {
                val art = artworkData
                if (art != null) {
                    setArtworkData(art, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                }
            }
            .build()
        return MediaItem.Builder()
            .setMediaId(id)
            .setUri(AutoMediaId.Item(state.itemId, state.episodeId.ifEmpty { null }).playbackUri())
            .setMediaMetadata(metadata)
            .build()
    }
}
