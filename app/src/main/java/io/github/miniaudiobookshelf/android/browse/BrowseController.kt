package io.github.miniaudiobookshelf.android.browse

import io.github.miniaudiobookshelf.core.ApiClient
import io.github.miniaudiobookshelf.core.ApiError
import io.github.miniaudiobookshelf.core.Chapter
import io.github.miniaudiobookshelf.core.Library
import io.github.miniaudiobookshelf.core.LibraryItem
import io.github.miniaudiobookshelf.core.PlaybackQueue
import io.github.miniaudiobookshelf.core.PlaybackStarter
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
        _ui.update { it.copy(selected = library, query = "", error = null, searching = false) }
        loadHome(library)
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
                _ui.update { it.copy(loading = false, error = "Couldn't load this book") }
            }
        }
    }

    fun closeDetail() {
        _ui.update { it.copy(detail = null, detailEpisodeId = "", newestFirst = true, error = null) }
    }

    fun onChapter(@Suppress("UNUSED_PARAMETER") chapter: Chapter) {
    }

    fun toggleEpisodeSort() {
        _ui.update { it.copy(newestFirst = !it.newestFirst) }
    }

    fun play(item: LibraryItem, startOver: Boolean = false) {
        val episodeId = item.episodeId.ifEmpty { null }
        playback.play(item, episodeId, startOver)
    }

    fun enqueue(episode: LibraryItem, show: LibraryItem) {
        queue.append(
            QueueEntry(
                itemId = show.id.ifEmpty { episode.id },
                episodeId = episode.episodeId,
                title = ViewLogic.episodeLabel(episode),
                showTitle = show.title.ifEmpty { episode.author },
            ),
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

    private fun loadHome(library: Library) {
        homeJob?.cancel()
        homeJob = scope.launch {
            _ui.update { it.copy(loading = true, error = null, searching = false) }
            try {
                val items = withContext(Dispatchers.IO) {
                    if (ViewLogic.isPodcastLibrary(library)) {
                        api.fetchRecentEpisodes(library.id, 50).results
                    } else {
                        api.fetchInProgress(library.id, 25).results
                    }
                }
                _ui.update { it.copy(items = items, loading = false, empty = items.isEmpty(), error = null) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: ApiError.Unauthorized) {
                onSessionExpired()
            } catch (_: Exception) {
                _ui.update { it.copy(loading = false, error = "Couldn't load library") }
            }
        }
    }

    private suspend fun search(library: Library, query: String) {
        homeJob?.cancel()
        _ui.update { it.copy(loading = true, error = null, searching = true) }
        try {
            val json = withContext(Dispatchers.IO) { api.search(library.id, query) }
            val hits = ViewLogic.searchHits(json, ViewLogic.searchMediaKey(library))
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
    val loading: Boolean = false,
    val empty: Boolean = false,
    val error: String? = null,
    val detail: LibraryItem? = null,
    val detailEpisodeId: String = "",
    val newestFirst: Boolean = true,
)
