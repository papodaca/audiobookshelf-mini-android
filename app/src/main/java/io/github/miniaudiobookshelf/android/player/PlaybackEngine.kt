package io.github.miniaudiobookshelf.android.player

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import io.github.miniaudiobookshelf.core.ApiClient
import io.github.miniaudiobookshelf.core.ApiError
import io.github.miniaudiobookshelf.core.AudioTrack
import io.github.miniaudiobookshelf.core.BookTimeline
import io.github.miniaudiobookshelf.core.DownloadCatalog
import io.github.miniaudiobookshelf.core.DownloadPolicy
import io.github.miniaudiobookshelf.core.EmptyDownloadCatalog
import io.github.miniaudiobookshelf.core.LocalDeviceInfo
import io.github.miniaudiobookshelf.core.LocalMediaMetadata
import io.github.miniaudiobookshelf.core.LocalPlaybackSession
import io.github.miniaudiobookshelf.core.PlaybackError
import io.github.miniaudiobookshelf.core.SettingsStore
import io.github.miniaudiobookshelf.core.SleepTimerPolicy
import io.github.miniaudiobookshelf.core.StallDecision
import io.github.miniaudiobookshelf.core.StallPolicy
import io.github.miniaudiobookshelf.core.TrackUriResolver
import io.github.miniaudiobookshelf.core.ViewLogic
import io.github.miniaudiobookshelf.android.download.LocalSessionFlush
import io.github.miniaudiobookshelf.android.download.LocalSessionStore
import io.github.miniaudiobookshelf.android.download.ServerReachability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@UnstableApi
class PlaybackEngine(
    private val api: ApiClient,
    private val deviceId: String,
    private val player: ExoPlayer,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val stateHolder: MutableStateFlow<PlayerState>,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    private val catalog: DownloadCatalog = EmptyDownloadCatalog,
    private val reachability: ServerReachability? = null,
    private val localSessions: LocalSessionStore? = null,
    private val localFlush: LocalSessionFlush? = null,
    private val signedIn: () -> Boolean = { true },
) {
    val state: StateFlow<PlayerState> = stateHolder
    var lastServerCurrentTime: Double = 0.0
        private set
    val currentSessionId: String get() = sessionId.orEmpty()
    val usesLiveSync: Boolean get() = liveSession && !sessionId.isNullOrEmpty()

    private var sessionId: String? = null
    private var liveSession = true
    private var tracks: List<AudioTrack> = emptyList()
    private var transitionInFlight = false
    private var pendingSeek = -1.0
    private var suppressPosition = false
    private var stallAutoRetry = false
    private var reopenScheduled = false
    private var stallRetryJob: Job? = null
    private var positionJob: Job? = null
    private val stall = StallPolicy()
    private val sleep = SleepTimerPolicy()
    private val listener = EngineListener()

    init {
        player.addListener(listener)
        val saved = ViewLogic.clampRate(settings.getPlaybackRate())
        stateHolder.update { it.copy(rate = saved) }
    }

    fun playItem(itemId: String, episodeId: String?, startOverride: Double) {
        scope.launch { playItemInternal(itemId, episodeId, startOverride) }
    }

    fun reopenSession() {
        val current = stateHolder.value
        if (current.itemId.isEmpty()) return
        playItem(current.itemId, current.episodeId.ifEmpty { null }, current.position)
    }

    fun seekBookTime(bookTime: Double) {
        hop {
            cancelStallRetry()
            stallAutoRetry = false
            stall.reset()
            if (transitionInFlight) {
                pendingSeek = bookTime
                return@hop
            }
            applySeek(bookTime)
            stateHolder.update { it.copy(seekGeneration = it.seekGeneration + 1) }
        }
    }

    fun skip(deltaSeconds: Double) {
        val current = stateHolder.value
        seekBookTime((current.position + deltaSeconds).coerceIn(0.0, current.duration.coerceAtLeast(0.0)))
    }

    fun previousChapter() {
        val current = stateHolder.value
        val timeline = current.timeline ?: return
        seekBookTime(timeline.previousChapterTarget(current.position))
    }

    fun nextChapter() {
        val current = stateHolder.value
        val timeline = current.timeline ?: return
        seekBookTime(timeline.nextChapterTarget(current.position))
    }

    fun setRate(rate: Double) {
        hop {
            val clamped = ViewLogic.clampRate(rate)
            settings.setPlaybackRate(clamped)
            stateHolder.update { it.copy(rate = clamped) }
            applyRate()
            if (player.playbackState != Player.STATE_IDLE) {
                applySeek(stateHolder.value.position)
            }
        }
    }

    fun pause() {
        hop {
            cancelStallRetry()
            stallAutoRetry = false
            // Documented behaviour: any pause cancels the sleep timer.
            if (sleep.isActive) {
                sleep.clear()
                publishSleepState()
            }
            stateHolder.update { it.copy(intended = IntendedState.PAUSED) }
            player.pause()
        }
    }

    fun setSleepTimerMinutes(minutes: Int) {
        hop {
            sleep.setMinutes(minutes, SystemClock.elapsedRealtime())
            publishSleepState()
        }
    }

    fun setSleepTimerEndOfChapter() {
        hop {
            val current = stateHolder.value
            val timeline = current.timeline ?: return@hop
            sleep.setEndOfChapter(timeline, current.position)
            publishSleepState()
        }
    }

    fun clearSleepTimer() {
        hop {
            sleep.clear()
            publishSleepState()
        }
    }

    private fun publishSleepState() {
        val remaining = sleep.remainingMs(SystemClock.elapsedRealtime())?.let { (it + 999) / 1000 }
        stateHolder.update {
            it.copy(
                sleepTimerActive = sleep.isActive,
                sleepEndOfChapter = sleep.isEndOfChapter,
                sleepRemainingSeconds = remaining,
            )
        }
    }

    fun resume() {
        hop {
            stateHolder.update { it.copy(intended = IntendedState.PLAYING, stalled = false, statusMessage = "") }
            player.play()
        }
    }

    fun stop() {
        scope.launch {
            hopWait {
                cancelStallRetry()
                stall.reset()
                stateHolder.update { it.copy(intended = IntendedState.STOPPED) }
                stopPositionTimer()
                player.stop()
                player.clearMediaItems()
            }
            closeSession()
            hopWait {
                stateHolder.update { it.copy(pipeline = PipelineState.STOPPED) }
            }
        }
    }

    fun retry() {
        reopenSession()
    }

    fun setSyncUnhealthy(value: Boolean) {
        hop {
            stateHolder.update { it.copy(syncUnhealthy = value) }
        }
    }

    fun raiseConflict(local: Double, server: Double) {
        hop {
            pause()
            stateHolder.update { it.copy(conflict = PlaybackConflict(local, server)) }
        }
    }

    fun resolveConflict(keepLocal: Boolean) {
        hop {
            val conflict = stateHolder.value.conflict ?: return@hop
            stateHolder.update { it.copy(conflict = null) }
            if (!keepLocal) {
                seekBookTime(conflict.serverPosition)
            }
            resume()
        }
    }

    suspend fun teardown() {
        hopWait {
            cancelStallRetry()
            stall.reset()
            stateHolder.update { it.copy(intended = IntendedState.STOPPED) }
            stopPositionTimer()
            if (player.playbackState != Player.STATE_IDLE) {
                player.pause()
            }
        }
        closeSession()
        hopWait {
            stateHolder.update { it.copy(pipeline = PipelineState.STOPPED, itemId = "") }
        }
    }

    fun acknowledgeFinished() {
        hop {
            stateHolder.update { it.copy(finished = false) }
        }
    }

    fun release() {
        player.removeListener(listener)
        stopPositionTimer()
        cancelStallRetry()
    }

    private suspend fun playItemInternal(itemId: String, episodeId: String?, startOverride: Double) {
        val current = stateHolder.value
        val sameItem = current.itemId == itemId &&
            current.episodeId == episodeId.orEmpty() &&
            current.intended != IntendedState.STOPPED
        if (sameItem && startOverride < 0) {
            android.util.Log.i(TAG, "already on $itemId, skip restart")
            if (current.intended != IntendedState.PLAYING) resume()
            return
        }
        if (transitionInFlight) return
        cancelStallRetry()
        if (sleep.isActive) {
            sleep.clear()
            hopWait { publishSleepState() }
        }
        if (!stallAutoRetry) stall.reset()
        stallAutoRetry = false
        transitionInFlight = true
        try {
            hopWait {
                stateHolder.update {
                    it.copy(
                        itemId = itemId,
                        episodeId = episodeId.orEmpty(),
                        intended = IntendedState.PLAYING,
                        pipeline = PipelineState.BUFFERING,
                        statusMessage = "",
                        retryAvailable = false,
                    )
                }
            }
            if (!signedIn()) {
                hopWait {
                    stateHolder.update {
                        it.copy(
                            statusMessage = "Sign in to play.",
                            retryAvailable = false,
                            pipeline = PipelineState.STOPPED,
                            intended = IntendedState.STOPPED,
                        )
                    }
                }
                return
            }
            val ep = episodeId.orEmpty()
            val complete = catalog.isComplete(itemId, ep)
            if (!complete) {
                val sidecar = catalog.sidecar(itemId, ep)
                val row = catalog.record(itemId, ep)
                if (row?.status == io.github.miniaudiobookshelf.core.DownloadStatus.COMPLETE || sidecar != null) {
                    catalog.markFailed(itemId, ep, "Downloaded files are incomplete or the size changed")
                }
            }
            val reachable = reachability?.isReachable() ?: true
            val mode = DownloadPolicy.playMode(complete, reachable)
            when (mode) {
                DownloadPolicy.PlayMode.LIVE_LOCAL, DownloadPolicy.PlayMode.LIVE_STREAM -> {
                    try {
                        openSession(itemId, episodeId, startOverride, useLocal = complete)
                    } catch (e: PlaybackError.TranscodeOnly) {
                        if (!DownloadPolicy.fallbackToOffline(mode)) throw e
                        openOffline(itemId, episodeId, startOverride)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (!DownloadPolicy.fallbackToOffline(mode)) throw e
                        android.util.Log.w(TAG, "live session failed, playing download for item $itemId")
                        openOffline(itemId, episodeId, startOverride)
                    }
                }
                DownloadPolicy.PlayMode.OFFLINE_LOCAL ->
                    openOffline(itemId, episodeId, startOverride)
                DownloadPolicy.PlayMode.CANNOT_PLAY -> hopWait {
                    stateHolder.update {
                        it.copy(
                            statusMessage = "This title isn't downloaded and the server could not be reached.",
                            retryAvailable = true,
                            pipeline = PipelineState.ERROR,
                            intended = IntendedState.STOPPED,
                        )
                    }
                }
            }
        } catch (e: PlaybackError.TranscodeOnly) {
            hopWait {
                stateHolder.update {
                    it.copy(
                        statusMessage = e.message.orEmpty(),
                        retryAvailable = false,
                        pipeline = PipelineState.STOPPED,
                        intended = IntendedState.STOPPED,
                    )
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            hopWait {
                stateHolder.update {
                    it.copy(
                        statusMessage = "The player could not start.",
                        retryAvailable = true,
                        pipeline = PipelineState.ERROR,
                    )
                }
            }
        } finally {
            transitionInFlight = false
            val queued = pendingSeek
            pendingSeek = -1.0
            if (queued >= 0) {
                hopWait { applySeek(queued) }
            }
        }
    }

    private suspend fun openSession(itemId: String, episodeId: String?, startOverride: Double, useLocal: Boolean) {
        closeSession()
        val session = withContext(Dispatchers.IO) {
            api.play(itemId, deviceId, episodeId)
        }
        if (!session.isDirectPlay) {
            withContext(Dispatchers.IO) {
                try {
                    api.closeSession(session.id)
                } catch (_: Exception) {
                    android.util.Log.w(TAG, "failed to close transcode-only session for item $itemId")
                }
            }
            throw PlaybackError.TranscodeOnly()
        }
        hopWait {
            liveSession = true
            val localUris = if (useLocal) catalog.localUris(itemId, episodeId.orEmpty()) else emptyList()
            val streamUris = session.tracks.map { api.streamUrl(session.id, it.index) }
            val uris = TrackUriResolver.playlistUris(useLocal, localUris, streamUris)
            attachPlaylist(itemId, episodeId, session.title, session.author, session.tracks, session.chapters, session.currentTime, startOverride, session.id, uris, coverUrl(itemId, episodeId.orEmpty()))
        }
    }

    private suspend fun openOffline(itemId: String, episodeId: String?, startOverride: Double) {
        closeSession()
        val sidecar = catalog.sidecar(itemId, episodeId.orEmpty())
        val uris = catalog.localUris(itemId, episodeId.orEmpty())
        if (sidecar == null || uris.isEmpty()) {
            hopWait {
                stateHolder.update {
                    it.copy(
                        statusMessage = "This download is incomplete.",
                        retryAvailable = false,
                        pipeline = PipelineState.ERROR,
                        intended = IntendedState.STOPPED,
                    )
                }
            }
            return
        }
        val stored = localSessions?.get(itemId, episodeId.orEmpty())
        val unsyncedTime = if (localSessions?.hasUnsynced(itemId, episodeId.orEmpty()) == true) {
            stored?.currentTime
        } else {
            null
        }
        val start = DownloadPolicy.offlineStart(
            startOverride = startOverride,
            unsyncedLocalTime = unsyncedTime,
            sidecar = sidecar,
            storedTime = stored?.currentTime,
        )
        val now = System.currentTimeMillis()
        val local = LocalPlaybackSession(
            id = stored?.id ?: LocalPlaybackSession.newId(),
            libraryItemId = itemId,
            episodeId = episodeId?.ifEmpty { null },
            mediaType = if (episodeId.isNullOrEmpty()) "book" else "podcast",
            duration = sidecar.timeline().totalDuration,
            currentTime = start,
            timeListening = stored?.timeListening ?: 0.0,
            startTime = stored?.startTime ?: start,
            startedAt = stored?.startedAt ?: now,
            updatedAt = now,
            displayTitle = sidecar.title,
            displayAuthor = sidecar.author,
            mediaMetadata = LocalMediaMetadata(title = sidecar.title),
            deviceInfo = LocalDeviceInfo(deviceId = deviceId),
        )
        localSessions?.upsert(local)
        hopWait {
            liveSession = false
            val tracks = sidecar.tracks.map {
                AudioTrack(
                    index = it.index,
                    startOffset = it.startOffset,
                    duration = it.duration,
                    title = it.title,
                    mimeType = it.mimeType,
                    ino = it.ino,
                    byteSize = it.byteSize,
                )
            }
            val chapters = sidecar.chapters.map {
                io.github.miniaudiobookshelf.core.Chapter(id = it.id, start = it.start, end = it.end, title = it.title)
            }
            attachPlaylist(itemId, episodeId, sidecar.title, sidecar.author, tracks, chapters, start, start, local.id, uris, coverUrl(itemId, episodeId.orEmpty()))
        }
    }

    private fun coverUrl(itemId: String, episodeId: String): String {
        val sidecar = catalog.sidecar(itemId, episodeId)
        if (sidecar != null) {
            val file = java.io.File(catalog.record(itemId, episodeId)?.sidecarPath.orEmpty()).parentFile
            val cover = file?.let { java.io.File(it, sidecar.coverFileName) }
            if (cover != null && cover.isFile) return cover.toUri().toString()
        }
        return api.coverUrl(itemId)
    }

    private fun attachPlaylist(
        itemId: String,
        episodeId: String?,
        title: String,
        author: String,
        sessionTracks: List<AudioTrack>,
        chapters: List<io.github.miniaudiobookshelf.core.Chapter>,
        serverCurrentTime: Double,
        startOverride: Double,
        newSessionId: String,
        uris: List<String>,
        artUrl: String,
    ) {
        sessionId = newSessionId
        lastServerCurrentTime = serverCurrentTime
        tracks = sessionTracks
        val timeline = BookTimeline(sessionTracks, chapters)
        val rate = ViewLogic.clampRate(settings.getPlaybackRate())
        val start = if (startOverride >= 0) startOverride else serverCurrentTime
        val pos = timeline.resolve(start)
        stateHolder.update {
            it.copy(
                itemId = itemId,
                episodeId = episodeId.orEmpty(),
                title = title,
                author = author,
                artUrl = artUrl,
                timeline = timeline,
                duration = timeline.totalDuration,
                rate = rate,
                intended = IntendedState.PLAYING,
                statusMessage = "",
                retryAvailable = false,
                stalled = false,
                conflict = null,
                currentTrackIndex = pos.index,
                positionSuppressed = false,
                position = start,
                chapter = timeline.chapterAt(start),
                finished = false,
            )
        }
        settings.setLastItemId(itemId)
        settings.setLastEpisodeId(episodeId.orEmpty())
        settings.setLastTitle(title)
        settings.setLastAuthor(author)
        val items = sessionTracks.mapIndexed { i, track ->
            val uri = uris.getOrNull(i) ?: uris.getOrNull(0) ?: return@mapIndexed null
            MediaItem.Builder()
                .setUri(uri.toUri())
                .setMediaId(itemId)
                .setMimeType(track.mimeType.ifEmpty { null })
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(title)
                        .setArtist(author)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
                        .build(),
                )
                .build()
        }.filterNotNull()
        val playlistIndex = sessionTracks.indexOfFirst { it.index == pos.index }.coerceAtLeast(0)
        val localMs = (pos.localOffset * 1000.0).toLong().coerceAtLeast(0L)
        suppressPosition = false
        player.setMediaItems(items, playlistIndex.coerceAtMost((items.size - 1).coerceAtLeast(0)), localMs)
        applyRate()
        player.prepare()
        player.play()
        startPositionTimer()
    }

    private fun applySeek(bookTime: Double) {
        val current = stateHolder.value
        val timeline = current.timeline ?: return
        val pos = timeline.resolve(bookTime)
        val playlistIndex = tracks.indexOfFirst { it.index == pos.index }.coerceAtLeast(0)
        val localMs = (pos.localOffset * 1000.0).toLong().coerceAtLeast(0L)
        val crossing = pos.index != current.currentTrackIndex
        if (crossing) {
            stateHolder.update { it.copy(positionSuppressed = true, currentTrackIndex = pos.index) }
        }
        applyRate()
        player.seekTo(playlistIndex, localMs)
        if (crossing) {
            stateHolder.update { it.copy(positionSuppressed = false) }
        }
        stateHolder.update { it.publishPosition(bookTime) }
    }

    private fun applyRate() {
        val rate = stateHolder.value.rate.toFloat()
        player.playbackParameters = PlaybackParameters(rate, 1f)
    }

    private fun pollPosition() {
        val current = stateHolder.value
        val suppressed = suppressPosition || current.positionSuppressed
        var book = current.position
        val timeline = current.timeline
        if (timeline != null && !suppressed && player.playbackState != Player.STATE_IDLE) {
            val local = player.currentPosition / 1000.0
            book = timeline.bookTime(current.currentTrackIndex, local)
            stateHolder.update { it.publishPosition(book) }
        }
        if (current.pipeline == PipelineState.ERROR || transitionInFlight) return
        val now = SystemClock.elapsedRealtime() / 1000.0
        val playing = current.intended == IntendedState.PLAYING
        applyStall(stall.sample(book, playing, now, suppressed))
        if (sleep.isActive) {
            if (playing && sleep.shouldPause(SystemClock.elapsedRealtime(), book)) {
                sleep.clear()
                publishSleepState()
                pause()
            } else {
                val remaining = sleep.remainingMs(SystemClock.elapsedRealtime())?.let { (it + 999) / 1000 }
                if (remaining != stateHolder.value.sleepRemainingSeconds) {
                    stateHolder.update { it.copy(sleepRemainingSeconds = remaining) }
                }
            }
        }
    }

    private fun applyStall(decision: StallDecision) {
        when (decision) {
            StallDecision.RETRY -> scheduleStallRetry()
            StallDecision.FAIL -> {
                cancelStallRetry()
                stateHolder.update {
                    it.copy(
                        statusMessage = "Playback stalled.",
                        retryAvailable = true,
                        stalled = true,
                    )
                }
            }
            StallDecision.CONTINUE -> Unit
        }
    }

    private fun scheduleStallRetry() {
        if (stallRetryJob != null || transitionInFlight) return
        stallAutoRetry = true
        val delayMs = (stall.consecutiveRetries * 1000).coerceAtLeast(1000).toLong()
        stallRetryJob = scope.launch {
            delay(delayMs)
            stallRetryJob = null
            try {
                reopenSession()
            } catch (_: Exception) {
                android.util.Log.w(TAG, "stall retry failed for item ${stateHolder.value.itemId}")
                stateHolder.update {
                    it.copy(
                        pipeline = PipelineState.ERROR,
                        statusMessage = "Playback stalled.",
                        retryAvailable = true,
                        stalled = true,
                    )
                }
            }
        }
    }

    private fun cancelStallRetry() {
        stallRetryJob?.cancel()
        stallRetryJob = null
    }

    private fun startPositionTimer() {
        stopPositionTimer()
        positionJob = scope.launch {
            while (isActive) {
                hopWait { pollPosition() }
                delay(250)
            }
        }
    }

    private fun stopPositionTimer() {
        positionJob?.cancel()
        positionJob = null
    }

    private suspend fun closeSession() {
        val id = sessionId ?: return
        sessionId = null
        if (liveSession) {
            withContext(Dispatchers.IO) {
                try {
                    api.closeSession(id)
                } catch (_: Exception) {
                    android.util.Log.w(TAG, "failed to close playback session for item ${stateHolder.value.itemId}")
                }
            }
        } else {
            localFlush?.flush()
        }
    }

    fun noteLocalListen(position: Double, delta: Double, duration: Double, nowMillis: Long) {
        val id = sessionId ?: return
        if (liveSession) return
        localSessions?.addListened(id, position, delta, duration, nowMillis)
    }

    fun flushLocal() {
        localFlush?.flushAsync()
    }

    private fun onTrackTransition(reason: Int) {
        if (tracks.isEmpty()) return
        val playlistIndex = player.currentMediaItemIndex.coerceIn(0, tracks.lastIndex)
        val nextIndex = tracks[playlistIndex].index
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
            suppressPosition = true
            stateHolder.update { it.copy(positionSuppressed = true, currentTrackIndex = nextIndex) }
            applyRate()
            mainHandler.postDelayed({
                suppressPosition = false
                stateHolder.update { it.copy(positionSuppressed = false) }
                pollPosition()
            }, 400)
        } else {
            stateHolder.update { it.copy(currentTrackIndex = nextIndex) }
            applyRate()
        }
    }

    private fun handlePlayerError(error: PlaybackException) {
        val itemId = stateHolder.value.itemId
        val track = stateHolder.value.currentTrackIndex
        val http = httpStatus(error)
        android.util.Log.w(TAG, "playback error on item $itemId track $track http $http")
        if (http == 404 || error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND) {
            scheduleReopen()
            return
        }
        if (error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS && http == 401) {
            stateHolder.update {
                it.copy(
                    pipeline = PipelineState.ERROR,
                    statusMessage = "Playback was not authorized.",
                    retryAvailable = true,
                )
            }
            return
        }
        stateHolder.update {
            it.copy(
                pipeline = PipelineState.ERROR,
                statusMessage = "Playback failed.",
                retryAvailable = true,
            )
        }
    }

    private fun scheduleReopen() {
        if (reopenScheduled || stateHolder.value.itemId.isEmpty()) return
        reopenScheduled = true
        mainHandler.post {
            try {
                reopenSession()
            } finally {
                reopenScheduled = false
            }
        }
    }

    private fun onEnded() {
        stateHolder.update {
            it.copy(intended = IntendedState.STOPPED, pipeline = PipelineState.STOPPED, finished = true)
        }
        scope.launch { closeSession() }
    }

    private inner class EngineListener : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            hop {
                when (playbackState) {
                    Player.STATE_BUFFERING -> stateHolder.update { it.copy(pipeline = PipelineState.BUFFERING) }
                    Player.STATE_READY -> {
                        val playing = player.playWhenReady
                        stateHolder.update {
                            it.copy(pipeline = if (playing) PipelineState.PLAYING else PipelineState.PAUSED)
                        }
                    }
                    Player.STATE_ENDED -> onEnded()
                    Player.STATE_IDLE -> {
                        if (stateHolder.value.intended == IntendedState.STOPPED) {
                            stateHolder.update { it.copy(pipeline = PipelineState.STOPPED) }
                        }
                    }
                }
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            hop {
                if (stateHolder.value.pipeline == PipelineState.BUFFERING) return@hop
                stateHolder.update {
                    it.copy(pipeline = if (playWhenReady) PipelineState.PLAYING else PipelineState.PAUSED)
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            hop { handlePlayerError(error) }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            hop { onTrackTransition(reason) }
        }
    }

    private fun hop(block: () -> Unit) {
        if (Looper.myLooper() == mainHandler.looper) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private suspend fun hopWait(block: () -> Unit) {
        if (Looper.myLooper() == mainHandler.looper) {
            block()
        } else {
            withContext(Dispatchers.Main.immediate) { block() }
        }
    }

    companion object {
        private const val TAG = "MiniAbsPlayer"

        fun httpStatus(error: PlaybackException): Int {
            var cause: Throwable? = error
            while (cause != null) {
                if (cause is HttpDataSource.InvalidResponseCodeException) {
                    return cause.responseCode
                }
                cause = cause.cause
            }
            return 0
        }
    }
}
