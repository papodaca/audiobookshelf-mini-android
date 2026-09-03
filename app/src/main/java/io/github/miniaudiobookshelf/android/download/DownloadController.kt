package io.github.miniaudiobookshelf.android.download

import android.content.Context
import androidx.work.ExistingWorkPolicy
import io.github.miniaudiobookshelf.core.ApiClient
import io.github.miniaudiobookshelf.core.DownloadRecord
import io.github.miniaudiobookshelf.core.DownloadStatus
import io.github.miniaudiobookshelf.core.LibraryItem
import io.github.miniaudiobookshelf.core.MemoryDownloadCatalog
import io.github.miniaudiobookshelf.core.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

class DownloadController(
    private val context: Context,
    private val repository: DownloadRepository,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val canDownload: () -> Boolean,
) {
    val records: StateFlow<List<DownloadRecord>> = repository.records
    private val progressMutex = Mutex()
    private var lastProgressRefreshAt = 0L

    fun record(itemId: String, episodeId: String): DownloadRecord? = repository.record(itemId, episodeId)

    fun enqueue(item: LibraryItem, episodeId: String) {
        if (!canDownload()) return
        val tracks = repository.downloadableTracks(
            if (episodeId.isEmpty()) item else item.episodeById(episodeId) ?: item,
        )
        if (tracks.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            val waiting = !settings.getAllowMeteredDownloads()
            repository.upsert(
                DownloadRecord(
                    itemId = item.id,
                    episodeId = episodeId,
                    title = if (episodeId.isEmpty()) item.title else (item.episodeById(episodeId)?.title ?: item.title),
                    author = if (episodeId.isEmpty()) item.author else item.title,
                    status = DownloadStatus.QUEUED,
                    bytesTotal = tracks.sumOf { it.byteSize.coerceAtLeast(0L) },
                    waitingUnmetered = waiting,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            DownloadWorker.enqueue(context, item.id, episodeId, settings.getAllowMeteredDownloads())
        }
    }

    fun pause(itemId: String, episodeId: String) {
        DownloadWorker.cancel(context, itemId, episodeId)
        scope.launch(Dispatchers.IO) {
            val row = repository.record(itemId, episodeId) ?: return@launch
            repository.upsert(row.copy(status = DownloadStatus.PAUSED, waitingUnmetered = false, updatedAt = System.currentTimeMillis()))
        }
    }

    fun cancel(itemId: String, episodeId: String) {
        DownloadWorker.cancel(context, itemId, episodeId)
        scope.launch(Dispatchers.IO) { repository.delete(itemId, episodeId) }
    }

    fun retry(itemId: String, episodeId: String) {
        val row = repository.record(itemId, episodeId) ?: return
        scope.launch(Dispatchers.IO) {
            repository.upsert(row.copy(status = DownloadStatus.QUEUED, error = "", waitingUnmetered = !settings.getAllowMeteredDownloads(), updatedAt = System.currentTimeMillis()))
            DownloadWorker.enqueue(context, itemId, episodeId, settings.getAllowMeteredDownloads(), ExistingWorkPolicy.REPLACE)
        }
    }

    fun delete(itemId: String, episodeId: String) {
        DownloadWorker.cancel(context, itemId, episodeId)
        scope.launch(Dispatchers.IO) { repository.delete(itemId, episodeId) }
    }

    fun deleteAll() {
        scope.launch(Dispatchers.IO) { repository.deleteAll() }
    }

    fun coverUri(itemId: String, episodeId: String): String = repository.coverUri(itemId, episodeId)

    fun backfillCovers(client: OkHttpClient, urlFor: (String) -> String) {
        scope.launch(Dispatchers.IO) {
            for (row in repository.records.value) {
                val existing = repository.coverFile(row.itemId, row.episodeId)
                if (existing.isFile && existing.length() > 0L) continue
                val url = urlFor(row.itemId)
                if (url.isEmpty()) continue
                if (repository.fetchCover(client, url, row.itemId, row.episodeId)) {
                    repository.upsert(row.copy(updatedAt = System.currentTimeMillis()))
                }
            }
        }
    }

    suspend fun refreshProgress(api: ApiClient, sessions: LocalSessionStore) {
        progressMutex.withLock {
            val complete = repository.records.value.filter { it.complete }
            if (complete.isEmpty()) return
            val now = System.currentTimeMillis()
            if (now - lastProgressRefreshAt < 15_000) return
            val all = try {
                withContext(Dispatchers.IO) { api.fetchAllProgress() }
            } catch (_: Exception) {
                return
            }
            lastProgressRefreshAt = now
            val byKey = all.associateBy { MemoryDownloadCatalog.catalogKey(it.libraryItemId, it.episodeId) }
            for (row in complete) {
                val progress = byKey[MemoryDownloadCatalog.catalogKey(row.itemId, row.episodeId)] ?: continue
                repository.cacheProgress(progress)
                sessions.applyServerProgress(row.itemId, row.episodeId, progress.currentTime, progress.duration, now)
            }
        }
    }

    fun setAllowMetered(value: Boolean) {
        settings.setAllowMeteredDownloads(value)
    }

    fun allowMetered(): Boolean = settings.getAllowMeteredDownloads()
}
