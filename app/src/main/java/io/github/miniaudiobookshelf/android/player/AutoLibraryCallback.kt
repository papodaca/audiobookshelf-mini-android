@file:OptIn(UnstableApi::class)

package io.github.miniaudiobookshelf.android.player

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import io.github.miniaudiobookshelf.core.AutoBrowseTree
import io.github.miniaudiobookshelf.core.AutoControllerAllowlist
import io.github.miniaudiobookshelf.core.AutoMediaId
import io.github.miniaudiobookshelf.core.AutoNode
import kotlinx.coroutines.launch

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
        if (!AutoControllerAllowlist.isAllowed(controller.packageName, service.packageName)) {
            return MediaSession.ConnectionResult.reject()
        }
        return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            .setAvailableSessionCommands(
                MediaSession.ConnectionResult.DEFAULT_SESSION_AND_PLAYER_COMMANDS.sessionCommands,
            )
            .setAvailablePlayerCommands(
                MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
                    .buildUpon()
                    .add(Player.COMMAND_SEEK_BACK)
                    .add(Player.COMMAND_SEEK_FORWARD)
                    .build(),
            )
            .build()
    }

    override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) {
        if (!service.isSignedIn()) {
            service.sendSignInError(session)
        }
    }

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibrarySession.LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        service.noteRootHints(params)
        val extras = Bundle()
        extras.putBoolean(SEARCH_SUPPORTED, true)
        val rootParams = params ?: MediaLibrarySession.LibraryParams.Builder().setExtras(extras).build()
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
        params: MediaLibrarySession.LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
        service.serviceScope.launch {
            try {
                val nodes = service.loadChildren(parentId)
                future.set(LibraryResult.ofItemList(ImmutableList.copyOf(nodes.map(::toMediaItem)), params))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                future.set(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
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
            is AutoMediaId.Item -> MediaItem.Builder()
                .setMediaId(mediaId)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setIsPlayable(true)
                        .setIsBrowsable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
                        .build(),
                )
                .build()
            else -> toMediaItem(AutoBrowseTree.rootItem())
        }
        return Futures.immediateFuture(LibraryResult.ofItem(item, null))
    }

    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: MediaLibrarySession.LibraryParams?,
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
        params: MediaLibrarySession.LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val items = if (query == lastSearchQuery) lastSearchItems else emptyList()
        return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
    }

    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val settings = service.settings()
        val itemId = settings.getLastItemId()
        if (itemId.isEmpty() || !service.isSignedIn()) {
            return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0))
        }
        val episodeId = settings.getLastEpisodeId().ifEmpty { null }
        val mediaId = AutoMediaId.Item(itemId, episodeId).encode()
        val item = MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .build(),
            )
            .build()
        return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(listOf(item), 0, 0))
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
            else -> MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
        }
        return MediaItem.Builder()
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
            .build()
    }

    companion object {
        const val SEARCH_SUPPORTED = "android.media.browse.SEARCH_SUPPORTED"
        const val ERROR_RESOLUTION_ACTION_LABEL = "android.media.extras.ERROR_RESOLUTION_ACTION_LABEL"
        const val ERROR_RESOLUTION_ACTION_INTENT = "android.media.extras.ERROR_RESOLUTION_ACTION_INTENT"
    }
}
