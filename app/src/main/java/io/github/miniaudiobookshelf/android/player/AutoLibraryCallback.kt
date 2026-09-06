package io.github.miniaudiobookshelf.android.player

import android.os.Bundle
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import io.github.miniaudiobookshelf.core.AutoBrowseTree
import io.github.miniaudiobookshelf.core.AutoMediaId
import io.github.miniaudiobookshelf.core.AutoNode
import kotlinx.coroutines.launch

@UnstableApi
class AutoLibraryCallback(
    private val service: PlaybackService,
) : MediaLibrarySession.Callback {
    @Volatile
    private var lastSearchQuery: String = ""

    @Volatile
    private var lastSearchItems: List<MediaItem> = emptyList()

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult {
        if (!service.isControllerAllowed(controller)) {
            Log.w(PlaybackService.AUTO_TAG, "reject connect pkg=${controller.packageName} uid=${controller.uid}")
            return MediaSession.ConnectionResult.reject()
        }
        Log.i(PlaybackService.AUTO_TAG, "connect pkg=${controller.packageName} uid=${controller.uid}")
        if (service.isCarController(controller)) {
            service.noteCarController()
        }
        return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            .setAvailableSessionCommands(
                MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                    .buildUpon()
                    .add(SessionCommand(PlaybackService.ACTION_SKIP_BACK_10, Bundle.EMPTY))
                    .add(SessionCommand(PlaybackService.ACTION_SKIP_FORWARD_10, Bundle.EMPTY))
                    .add(SessionCommand(PlaybackService.ACTION_CYCLE_SPEED, Bundle.EMPTY))
                    .build(),
            )
            .setAvailablePlayerCommands(
                MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
                    .buildUpon()
                    .add(Player.COMMAND_SEEK_BACK)
                    .add(Player.COMMAND_SEEK_FORWARD)
                    .add(Player.COMMAND_SET_SPEED_AND_PITCH)
                    .build(),
            )
            .build()
    }

    override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) {
        service.serviceScope.launch {
            service.awaitSessionSettled()
            if (!service.isSignedIn()) {
                service.sendSignInError(session)
            }
        }
    }

    override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
        Log.i(
            PlaybackService.AUTO_TAG,
            "disconnect pkg=${controller.packageName} uid=${controller.uid}",
        )
        if (!service.isCarController(controller)) return
        val stillCar = session.connectedControllers.any { other ->
            other !== controller && service.isCarController(other)
        }
        if (!stillCar) {
            service.pauseForCarDisconnect()
        }
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> {
        when (customCommand.customAction) {
            PlaybackService.ACTION_SKIP_BACK_10 -> {
                service.skipSeconds(-10.0)
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            PlaybackService.ACTION_SKIP_FORWARD_10 -> {
                service.skipSeconds(10.0)
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            PlaybackService.ACTION_CYCLE_SPEED -> {
                service.cycleSpeed()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
        }
        return super.onCustomCommand(session, controller, customCommand, args)
    }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<MutableList<MediaItem>> {
        val resolved = mediaItems.map(::withPlaybackUri).toMutableList()
        Log.i(
            PlaybackService.AUTO_TAG,
            "addMediaItems pkg=${controller.packageName} count=${resolved.size} ids=${resolved.joinToString { it.mediaId }}",
        )
        return Futures.immediateFuture(resolved)
    }

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        Log.i(
            PlaybackService.AUTO_TAG,
            "root pkg=${browser.packageName} recent=${params?.isRecent} offline=${params?.isOffline} suggested=${params?.isSuggested}",
        )
        service.noteRootHints(params)
        val extras = Bundle()
        extras.putBoolean(SEARCH_SUPPORTED, true)
        extras.putInt(
            MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_CATEGORY_LIST_ITEM,
        )
        extras.putInt(
            MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
        )
        val rootParams = LibraryParams.Builder()
            .setExtras(extras)
            .setRecent(params?.isRecent == true)
            .setOffline(params?.isOffline == true)
            .setSuggested(params?.isSuggested == true)
            .build()
        return Futures.immediateFuture(
            LibraryResult.ofItem(toMediaItem(AutoBrowseTree.rootItem()), rootParams),
        )
    }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
        service.serviceScope.launch {
            try {
                val nodes = if (params?.isRecent == true) {
                    listOfNotNull(service.lastPlayedNode()).ifEmpty { service.loadChildren(parentId) }
                } else {
                    service.loadChildren(parentId)
                }
                Log.i(
                    PlaybackService.AUTO_TAG,
                    "children parent=$parentId recent=${params?.isRecent} count=${nodes.size} signedIn=${service.isSignedIn()}",
                )
                future.set(LibraryResult.ofItemList(ImmutableList.copyOf(nodes.map(::toMediaItem)), params))
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (!future.isDone) {
                    future.set(
                        LibraryResult.ofItemList(
                            ImmutableList.copyOf(AutoBrowseTree.notice("Couldn't load library").map(::toMediaItem)),
                            params,
                        ),
                    )
                }
                throw e
            } catch (e: Exception) {
                Log.w(PlaybackService.AUTO_TAG, "children failed parent=$parentId", e)
                future.set(
                    LibraryResult.ofItemList(
                        ImmutableList.copyOf(AutoBrowseTree.notice("Couldn't load library").map(::toMediaItem)),
                        params,
                    ),
                )
            }
        }
        return future
    }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        val parsed = AutoMediaId.parse(mediaId)
        val item = when (parsed) {
            is AutoMediaId.Item -> {
                val last = service.lastPlayedNode()?.takeIf { it.id == parsed }
                MediaItem.Builder()
                    .setMediaId(mediaId)
                    .setUri(parsed.playbackUri())
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(last?.title)
                            .setArtist(last?.subtitle?.ifEmpty { null })
                            .setIsPlayable(true)
                            .setIsBrowsable(false)
                            .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
                            .build(),
                    )
                    .build()
            }
            else -> toMediaItem(AutoBrowseTree.rootItem())
        }
        return Futures.immediateFuture(LibraryResult.ofItem(item, null))
    }

    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> {
        val future = SettableFuture.create<LibraryResult<Void>>()
        service.serviceScope.launch {
            try {
                val hits = service.searchItems(query)
                lastSearchQuery = query
                lastSearchItems = hits
                session.notifySearchResultChanged(browser, query, hits.size, params)
                future.set(LibraryResult.ofVoid())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                lastSearchQuery = query
                lastSearchItems = emptyList()
                session.notifySearchResultChanged(browser, query, 0, params)
                future.set(LibraryResult.ofVoid())
            }
        }
        return future
    }

    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val items = if (query == lastSearchQuery) lastSearchItems else emptyList()
        return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
    }

    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val empty = MediaSession.MediaItemsWithStartPosition(emptyList(), 0, C.TIME_UNSET)
        if (!service.isSignedIn()) {
            return Futures.immediateFuture(empty)
        }
        val node = service.lastPlayedNode()
        if (node == null) {
            Log.i(PlaybackService.AUTO_TAG, "resumption empty pkg=${controller.packageName}")
            return Futures.immediateFuture(empty)
        }
        Log.i(
            PlaybackService.AUTO_TAG,
            "resumption pkg=${controller.packageName} mediaId=${node.id.encode()} title=${node.title}",
        )
        return Futures.immediateFuture(
            MediaSession.MediaItemsWithStartPosition(listOf(toMediaItem(node)), 0, C.TIME_UNSET),
        )
    }

    private fun toMediaItem(node: AutoNode): MediaItem {
        val type = when (val id = node.id) {
            is AutoMediaId.Item -> if (id.episodeId != null) {
                MediaMetadata.MEDIA_TYPE_PODCAST
            } else {
                MediaMetadata.MEDIA_TYPE_AUDIO_BOOK
            }
            AutoMediaId.Continue -> MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS
            AutoMediaId.Downloads -> MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS
            AutoMediaId.SignIn -> MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
            else -> MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
        }
        val builder = MediaItem.Builder()
            .setMediaId(node.id.encode())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(node.title)
                    .setSubtitle(node.subtitle.ifEmpty { null })
                    .setArtist(node.subtitle.ifEmpty { null })
                    .setIsBrowsable(node.browsable)
                    .setIsPlayable(node.playable)
                    .setMediaType(type)
                    .build(),
            )
        val itemId = node.id
        if (node.playable && itemId is AutoMediaId.Item) {
            builder.setUri(itemId.playbackUri())
        }
        return builder.build()
    }

    private fun withPlaybackUri(item: MediaItem): MediaItem {
        if (item.localConfiguration != null) return item
        val parsed = AutoMediaId.parse(item.mediaId) as? AutoMediaId.Item ?: return item
        return item.buildUpon().setUri(parsed.playbackUri()).build()
    }

    companion object {
        const val SEARCH_SUPPORTED = "android.media.browse.SEARCH_SUPPORTED"
        const val ERROR_RESOLUTION_ACTION_LABEL = "android.media.extras.ERROR_RESOLUTION_ACTION_LABEL"
        const val ERROR_RESOLUTION_ACTION_INTENT = "android.media.extras.ERROR_RESOLUTION_ACTION_INTENT"
    }
}
