@file:OptIn(UnstableApi::class)

package io.github.miniaudiobookshelf.android.player

import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import io.github.miniaudiobookshelf.core.AutoMediaId

class BookAwarePlayer(
    private val exo: ExoPlayer,
) : ForwardingPlayer(exo) {
    var engine: PlaybackEngine? = null
    var onPlayItem: ((itemId: String, episodeId: String?, startAt: Double) -> Unit)? = null
    var onPlayFromSearch: ((query: String) -> Unit)? = null

    private val extraListeners = LinkedHashSet<Player.Listener>()

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
        engine?.seekBookTime(positionMs / 1000.0)
    }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        engine?.seekBookTime(positionMs / 1000.0)
    }

    override fun play() {
        engine?.resume()
    }

    override fun pause() {
        engine?.pause()
    }

    override fun stop() {
        engine?.stop()
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
        val start = if (startPositionMs == C.TIME_UNSET || startPositionMs < 0) {
            -1.0
        } else {
            startPositionMs / 1000.0
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
            .setMediaMetadata(metadata)
            .build()
    }
}
