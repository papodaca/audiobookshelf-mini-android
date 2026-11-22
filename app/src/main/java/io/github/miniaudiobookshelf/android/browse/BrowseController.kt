package io.github.miniaudiobookshelf.android.browse

import io.github.miniaudiobookshelf.core.ApiClient
import io.github.miniaudiobookshelf.core.ApiError
import io.github.miniaudiobookshelf.core.AudioTrack
import io.github.miniaudiobookshelf.core.Chapter
import io.github.miniaudiobookshelf.core.DownloadCatalog
import io.github.miniaudiobookshelf.core.EmptyDownloadCatalog
import io.github.miniaudiobookshelf.core.Library
import io.github.miniaudiobookshelf.core.LibraryItem
import io.github.miniaudiobookshelf.core.Models
import io.github.miniaudiobookshelf.core.PlaybackQueue
import io.github.miniaudiobookshelf.core.PlaybackStarter
import io.github.miniaudiobookshelf.core.QueueAppendResult
import io.github.miniaudiobookshelf.core.QueueEntry
import io.github.miniaudiobookshelf.core.SettingsStore
import io.github.miniaudiobookshelf.core.ViewLogic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BrowseController(
    private val api: ApiClient,
    private val settings: SettingsStore,
    val queue: PlaybackQueue,
    val playback: PlaybackStarter,
    private val scope: CoroutineScope,
    private val downloads: DownloadCatalog = EmptyDownloadCatalog,
    var onSessionExpired: () -> Unit = {},
) {
    private val _ui = MutableStateFlow(BrowseUi())
    val ui: StateFlow<BrowseUi> = _ui
    private var searchJob: Job? = null
    private var homeJob: Job? = null
    private var libraries: List<Library> = emptyList()
    private val coverUrls = mutableMapOf<String, String>()

    fun start() {
        scope.launch { loadLibraries() }
    }

    fun coverUrl(itemId: String): String = coverUrls.getOrPut(itemId) { api.coverUrl(itemId) }

    fun refresh() {
        if (_ui.value.refreshing || _ui.value.detail != null) return
        val library = _ui.value.selected
        if (library == null) {
            scope.launch { loadLibraries() }
            return
        }
        _ui.update { it.copy(refreshing = true) }
        val query = _ui.value.query
        val job = if (query.isNotBlank()) {
            searchJob?.cancel()
            scope.launch { search(library, query) }.also { searchJob = it }
        } else {
            loadHome(library)
        }
        job.invokeOnCompletion { _ui.update { it.copy(refreshing = false) } }
    }

    fun retry() {
        val detail = _ui.value.detail
        if (detail != null) {
            openDetail(detail, _ui.value.detailEpisodeId.ifEmpty { null })
        } else {
            scope.launch { loadLibraries() }
        }
    }

    fun selectLibrary(library: Library) {
        searchJob?.cancel()
        settings.setLibraryId(library.id)
        playback.notifyLibraryChanged()
        _ui.update { it.copy(selected = library, query = "", error = null, searching = false, searchOpen = false) }
        loadHome(library)
    }

    fun openSearch() {
        _ui.update { it.copy(searchOpen = true) }
    }

    fun closeSearch() {
        searchJob?.cancel()
        val library = _ui.value.selected
        _ui.update { it.copy(searchOpen = false, query = "", searching = false, error = null) }
        if (library != null) {
            loadHome(library)
        }
    }

    fun onQuery(query: String) {
        _ui.update { it.copy(query = query) }
        searchJob?.cancel()
        val library = _ui.value.selected ?: return
        if (query.isBlank()) {
            loadHome(library)
            return
        }
        searchJob = scope.launch {
            delay(250)
            search(library, query)
        }
    }

    fun openDetail(item: LibraryItem, episodeId: String? = item.episodeId.ifEmpty { null }) {
        scope.launch {
            _ui.update {
                it.copy(
                    detail = item,
                    detailEpisodeId = episodeId.orEmpty(),
                    loading = true,
                    error = null,
                    newestFirst = true,
                )
            }
            try {
                val fetched = withContext(Dispatchers.IO) { api.fetchItem(item.id, episodeId) }
                _ui.update { it.copy(detail = fetched, loading = false, error = null) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: ApiError.Unauthorized) {
                onSessionExpired()
            } catch (_: Exception) {
                val sidecar = downloads.sidecar(item.id, episodeId.orEmpty())
                if (sidecar != null) {
                    val tracks = sidecar.tracks.map { track ->
                        AudioTrack(
                            index = track.index,
                            startOffset = track.startOffset,
                            duration = track.duration,
                            title = track.title,
                            mimeType = track.mimeType,
                            ino = track.ino,
                            byteSize = track.byteSize,
                        )
                    }
                    val offline = item.copy(
                        title = sidecar.title.ifEmpty { item.title },
                        author = sidecar.author.ifEmpty { item.author },
                        episodeId = sidecar.episodeId.ifEmpty { item.episodeId },
                        tracks = tracks,
                        numTracks = tracks.size,
                        chapters = sidecar.chapters.map { chapter ->
                            Chapter(id = chapter.id, start = chapter.start, end = chapter.end, title = chapter.title)
                        },
                        duration = sidecar.timeline().totalDuration,
                    )
                    _ui.update { it.copy(detail = offline, loading = false, error = null) }
                } else {
                    _ui.update { it.copy(loading = false, error = "Couldn't load this book") }
                }
            }
        }
    }

    fun closeDetail() {
        _ui.update { it.copy(detail = null, detailEpisodeId = "", newestFirst = true, error = null) }
    }

    fun onChapter(chapter: Chapter) {
        val item = _ui.value.detail ?: return
        val episodeId = _ui.value.detailEpisodeId.ifEmpty { null }
        playback.playAt(item, episodeId, chapter.start)
    }

    fun toggleEpisodeSort() {
        _ui.update { it.copy(newestFirst = !it.newestFirst) }
    }

    fun play(item: LibraryItem, startOver: Boolean = false) {
        val episodeId = item.episodeId.ifEmpty { null }
        playback.play(item, episodeId, startOver)
    }

    fun enqueue(episode: LibraryItem, show: LibraryItem) {
        val result = queue.append(
            QueueEntry(
                itemId = show.id.ifEmpty { episode.id },
                episodeId = episode.episodeId,
                title = ViewLogic.episodeLabel(episode),
                showTitle = show.title.ifEmpty { episode.author },
            ),
            currentItemId = playback.playingItemId(),
            currentEpisodeId = playback.playingEpisodeId(),
        )
        val notice = when (result) {
            QueueAppendResult.ADDED -> "Added to Up next"
            QueueAppendResult.DUPLICATE -> "Already in Up next"
            QueueAppendResult.CURRENT -> "That's already playing"
            QueueAppendResult.IGNORED -> null
        }
        if (notice != null) {
            _ui.update { it.copy(notice = notice) }
        }
    }

    fun dismissNotice() {
        _ui.update { it.copy(notice = null) }
    }

    fun playQueued(index: Int) {
        val entry = queue.takeAt(index) ?: return
        playEntry(entry)
    }

    fun removeQueued(index: Int) {
        queue.removeAt(index)
    }

    fun moveQueued(from: Int, to: Int) {
        queue.move(from, to)
    }

    fun clearQueue() {
        queue.clear()
    }

    fun playNextQueued(): Boolean {
        val entry = queue.popFront() ?: return false
        playEntry(entry)
        return true
    }

    private fun playEntry(entry: QueueEntry) {
        playback.play(
            LibraryItem(
                id = entry.itemId,
                episodeId = entry.episodeId,
                title = entry.title,
                author = entry.showTitle,
            ),
            episodeId = entry.episodeId.ifEmpty { null },
            startOver = false,
        )
    }

    private suspend fun loadLibraries() {
        _ui.update { it.copy(loading = true, error = null) }
        try {
            val list = withContext(Dispatchers.IO) { api.fetchLibraries() }
            libraries = list.libraries
            val selected = ViewLogic.pickLibrary(libraries, settings.getLibraryId())
            if (selected != null) {
                settings.setLibraryId(selected.id)
            }
            _ui.update { it.copy(libraries = libraries, selected = selected, loading = false) }
            if (selected != null) {
                loadHome(selected)
            } else {
                _ui.update { it.copy(items = emptyList(), empty = true) }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: ApiError.Unauthorized) {
            onSessionExpired()
        } catch (_: Exception) {
            _ui.update { it.copy(loading = false, error = "Couldn't load library") }
        }
    }

    private fun loadHome(library: Library): Job {
        homeJob?.cancel()
        val job = scope.launch {
            _ui.update { it.copy(loading = true, error = null, searching = false) }
            try {
                val fetched = withContext(Dispatchers.IO) {
                    if (ViewLogic.isPodcastLibrary(library)) {
                        api.fetchRecentEpisodes(library.id, 50).results
                    } else {
                        api.fetchInProgress(library.id, 25).results
                    }
                }
                val items = withProgress(fetched)
                _ui.update { it.copy(items = items, loading = false, empty = items.isEmpty(), error = null) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: ApiError.Unauthorized) {
                onSessionExpired()
            } catch (_: Exception) {
                _ui.update { it.copy(loading = false, error = "Couldn't load library") }
            }
        }
        homeJob = job
        return job
    }

    /**
     * List payloads carry no progress object, so attach the user's progress
     * from /api/me/progress (the same source DownloadController uses).
     * A failed progress fetch just leaves the items without progress.
     */
    private suspend fun withProgress(items: List<LibraryItem>): List<LibraryItem> {
        if (items.isEmpty() || items.all { it.progress != null }) return items
        val all = try {
            withContext(Dispatchers.IO) { api.fetchAllProgress() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            return items
        }
        return Models.attachProgress(items, all)
    }

    private suspend fun search(library: Library, query: String) {
        homeJob?.cancel()
        _ui.update { it.copy(loading = true, error = null, searching = true) }
        try {
            val json = withContext(Dispatchers.IO) { api.search(library.id, query) }
            val hits = withProgress(ViewLogic.searchHits(json, ViewLogic.searchMediaKey(library)))
            if (_ui.value.query != query) return
            _ui.update { it.copy(items = hits, loading = false, empty = hits.isEmpty(), error = null) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: ApiError.Unauthorized) {
            onSessionExpired()
        } catch (_: Exception) {
            if (_ui.value.query != query) return
            _ui.update { it.copy(loading = false, error = "Couldn't load library") }
        }
    }
}

data class BrowseUi(
    val libraries: List<Library> = emptyList(),
    val selected: Library? = null,
    val items: List<LibraryItem> = emptyList(),
    val query: String = "",
    val searching: Boolean = false,
    val searchOpen: Boolean = false,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val empty: Boolean = false,
    val error: String? = null,
    val detail: LibraryItem? = null,
    val detailEpisodeId: String = "",
    val newestFirst: Boolean = true,
    val notice: String? = null,
)
