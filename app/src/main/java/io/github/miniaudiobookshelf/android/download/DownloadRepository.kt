package io.github.miniaudiobookshelf.android.download

import android.content.Context
import android.net.Uri
import io.github.miniaudiobookshelf.core.DownloadCatalog
import io.github.miniaudiobookshelf.core.DownloadEntry
import io.github.miniaudiobookshelf.core.DownloadFiles
import io.github.miniaudiobookshelf.core.DownloadPolicy
import io.github.miniaudiobookshelf.core.DownloadRecord
import io.github.miniaudiobookshelf.core.DownloadSidecar
import io.github.miniaudiobookshelf.core.DownloadSidecarJson
import io.github.miniaudiobookshelf.core.DownloadStatus
import io.github.miniaudiobookshelf.core.LibraryItem
import io.github.miniaudiobookshelf.core.MediaProgress
import io.github.miniaudiobookshelf.core.MemoryDownloadCatalog
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request

class DownloadRepository(
    private val context: Context,
    private val dao: DownloadDao,
) : DownloadCatalog {
    private val cache = MutableStateFlow<List<DownloadRecord>>(emptyList())
    val records: StateFlow<List<DownloadRecord>> = cache

    val root: File
        get() = File(context.getExternalFilesDir(null), "abs-downloads")

    suspend fun hydrate() {
        cache.value = dao.all().map { entity ->
            val row = entity.toRecord()
            row.mergeProgress(sidecar(row.itemId, row.episodeId))
        }
    }

    fun dirFor(itemId: String, episodeId: String): File {
        return File(root, DownloadFiles.relativeDir(itemId, episodeId))
    }

    fun sidecarFile(itemId: String, episodeId: String): File {
        return File(dirFor(itemId, episodeId), SIDECAR_NAME)
    }

    fun audioFile(itemId: String, episodeId: String, index: Int): File {
        return File(dirFor(itemId, episodeId), DownloadFiles.audioFileName(index))
    }

    fun partFile(itemId: String, episodeId: String, index: Int): File {
        return File(dirFor(itemId, episodeId), DownloadFiles.audioFileName(index) + ".part")
    }

    fun coverFile(itemId: String, episodeId: String): File {
        return File(dirFor(itemId, episodeId), COVER_NAME)
    }

    fun coverUri(itemId: String, episodeId: String): String {
        val file = coverFile(itemId, episodeId)
        if (!file.isFile || file.length() <= 0L) return ""
        return Uri.fromFile(file).toString()
    }

    fun fetchCover(client: OkHttpClient, url: String, itemId: String, episodeId: String): Boolean {
        if (url.isEmpty()) return false
        val dest = coverFile(itemId, episodeId)
        if (dest.isFile && dest.length() > 0L) return true
        dest.parentFile?.mkdirs()
        val request = Request.Builder().url(url).get().build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return false
                val bytes = response.body?.bytes() ?: return false
                if (bytes.isEmpty()) return false
                val part = File(dest.absolutePath + ".part")
                part.writeBytes(bytes)
                if (dest.exists()) dest.delete()
                part.renameTo(dest) && dest.isFile && dest.length() > 0L
            }
        } catch (_: Exception) {
            false
        }
    }

    fun cacheProgress(progress: MediaProgress) {
        val sidecar = sidecar(progress.libraryItemId, progress.episodeId) ?: return
        val updated = sidecar.copy(
            currentTime = progress.currentTime,
            duration = if (progress.duration > 0) progress.duration else sidecar.duration,
            isFinished = progress.isFinished,
            progressUpdatedAt = if (progress.lastUpdate > 0) progress.lastUpdate else System.currentTimeMillis(),
        )
        writeSidecar(updated)
        val row = record(progress.libraryItemId, progress.episodeId) ?: return
        cache.update { current ->
            current.map { item ->
                if (item.itemId == row.itemId && item.episodeId == row.episodeId) {
                    item.mergeProgress(updated)
                } else {
                    item
                }
            }
        }
    }

    fun writeSidecar(sidecar: DownloadSidecar) {
        val file = sidecarFile(sidecar.itemId, sidecar.episodeId)
        file.parentFile?.mkdirs()
        file.writeText(DownloadSidecarJson.encode(sidecar))
    }

    suspend fun upsert(record: DownloadRecord) {
        dao.upsert(record.toEntity())
        cache.update { current ->
            val without = current.filterNot { it.itemId == record.itemId && it.episodeId == record.episodeId }
            (listOf(record) + without).sortedByDescending { it.updatedAt }
        }
    }

    override fun completeEntries(): List<DownloadEntry> {
        return cache.value.filter { isComplete(it.itemId, it.episodeId) }.map { it.toEntry() }
    }

    override fun record(itemId: String, episodeId: String): DownloadRecord? {
        return cache.value.firstOrNull { it.itemId == itemId && it.episodeId == episodeId }
    }

    override fun isComplete(itemId: String, episodeId: String): Boolean {
        val row = record(itemId, episodeId) ?: return false
        if (row.status != DownloadStatus.COMPLETE) return false
        val sidecar = sidecar(itemId, episodeId) ?: return false
        return DownloadPolicy.canPlayOffline(sidecar, fileSizes(itemId, episodeId, sidecar))
    }

    override fun localUris(itemId: String, episodeId: String): List<String> {
        val sidecar = sidecar(itemId, episodeId) ?: return emptyList()
        if (!isComplete(itemId, episodeId)) return emptyList()
        return sidecar.tracks.map { track ->
            Uri.fromFile(File(dirFor(itemId, episodeId), track.fileName)).toString()
        }
    }

    override fun sidecar(itemId: String, episodeId: String): DownloadSidecar? {
        val file = sidecarFile(itemId, episodeId)
        if (!file.isFile) return null
        return try {
            DownloadSidecarJson.decode(file.readText())
        } catch (_: Exception) {
            null
        }
    }

    override fun markFailed(itemId: String, episodeId: String, error: String) {
        val row = record(itemId, episodeId) ?: return
        runBlocking {
            upsert(
                row.copy(
                    status = DownloadStatus.FAILED,
                    error = error,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    suspend fun delete(itemId: String, episodeId: String) {
        dao.delete(MemoryDownloadCatalog.catalogKey(itemId, episodeId))
        dirFor(itemId, episodeId).deleteRecursively()
        cache.update { it.filterNot { row -> row.itemId == itemId && row.episodeId == episodeId } }
    }

    suspend fun deleteAll() {
        dao.deleteAll()
        root.deleteRecursively()
        cache.value = emptyList()
    }

    fun downloadableTracks(item: LibraryItem): List<io.github.miniaudiobookshelf.core.AudioTrack> {
        return item.tracks.filter { it.ino.isNotEmpty() }
    }

    private fun fileSizes(itemId: String, episodeId: String, sidecar: DownloadSidecar): Map<String, Long> {
        val dir = dirFor(itemId, episodeId)
        return sidecar.tracks.associate { track ->
            val file = File(dir, track.fileName)
            track.fileName to if (file.isFile) file.length() else -1L
        }
    }

    companion object {
        const val SIDECAR_NAME = "sidecar.json"
        const val COVER_NAME = "cover.jpg"
    }
}

private fun DownloadRecord.mergeProgress(sidecar: DownloadSidecar?): DownloadRecord {
    if (sidecar == null) return this
    return copy(
        currentTime = sidecar.currentTime,
        duration = sidecar.duration,
        isFinished = sidecar.isFinished,
    )
}

private fun DownloadRecord.toEntity(): DownloadEntity = DownloadEntity(
    rowKey = MemoryDownloadCatalog.catalogKey(itemId, episodeId),
    itemId = itemId,
    episodeId = episodeId,
    title = title,
    author = author,
    status = status.name,
    bytesDownloaded = bytesDownloaded,
    bytesTotal = bytesTotal,
    sidecarPath = sidecarPath,
    waitingUnmetered = waitingUnmetered,
    error = error,
    updatedAt = updatedAt,
)

private fun DownloadEntity.toRecord(): DownloadRecord = DownloadRecord(
    itemId = itemId,
    episodeId = episodeId,
    title = title,
    author = author,
    status = try {
        DownloadStatus.valueOf(status)
    } catch (_: Exception) {
        DownloadStatus.FAILED
    },
    bytesDownloaded = bytesDownloaded,
    bytesTotal = bytesTotal,
    sidecarPath = sidecarPath,
    waitingUnmetered = waitingUnmetered,
    error = error,
    updatedAt = updatedAt,
)
