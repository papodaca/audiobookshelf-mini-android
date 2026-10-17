package io.github.miniaudiobookshelf.core

import kotlinx.serialization.Serializable

enum class DownloadStatus {
    QUEUED,
    RUNNING,
    PAUSED,
    FAILED,
    COMPLETE,
}

data class DownloadEntry(
    val itemId: String,
    val episodeId: String = "",
    val title: String = "",
    val author: String = "",
    val complete: Boolean = false,
)

data class DownloadRecord(
    val itemId: String,
    val episodeId: String = "",
    val title: String = "",
    val author: String = "",
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val bytesDownloaded: Long = 0,
    val bytesTotal: Long = 0,
    val sidecarPath: String = "",
    val waitingUnmetered: Boolean = false,
    val error: String = "",
    val addedAt: Long = 0,
    val updatedAt: Long = 0,
    val currentTime: Double = 0.0,
    val duration: Double = 0.0,
    val isFinished: Boolean = false,
) {
    val complete: Boolean get() = status == DownloadStatus.COMPLETE

    fun toEntry(): DownloadEntry = DownloadEntry(
        itemId = itemId,
        episodeId = episodeId,
        title = title,
        author = author,
        complete = complete,
    )
}

@Serializable
data class SidecarTrack(
    val index: Int,
    val startOffset: Double,
    val duration: Double,
    val title: String = "",
    val mimeType: String = "",
    val fileName: String,
    val byteSize: Long = 0,
    val ino: String = "",
)

@Serializable
data class SidecarChapter(
    val id: Int = 0,
    val start: Double = 0.0,
    val end: Double = 0.0,
    val title: String = "",
)

@Serializable
data class DownloadSidecar(
    val itemId: String,
    val episodeId: String = "",
    val title: String = "",
    val author: String = "",
    val tracks: List<SidecarTrack> = emptyList(),
    val chapters: List<SidecarChapter> = emptyList(),
    val coverFileName: String = "cover.jpg",
    val currentTime: Double = 0.0,
    val duration: Double = 0.0,
    val isFinished: Boolean = false,
    val progressUpdatedAt: Long = 0,
) {
    fun timeline(): BookTimeline {
        val audio = tracks.map { track ->
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
        val chaps = chapters.map { chapter ->
            Chapter(id = chapter.id, start = chapter.start, end = chapter.end, title = chapter.title)
        }
        return BookTimeline(audio, chaps)
    }
}

interface DownloadCatalog {
    fun completeEntries(): List<DownloadEntry>
    fun record(itemId: String, episodeId: String): DownloadRecord?
    fun isComplete(itemId: String, episodeId: String): Boolean
    fun localUris(itemId: String, episodeId: String): List<String>
    fun sidecar(itemId: String, episodeId: String): DownloadSidecar?
    fun markFailed(itemId: String, episodeId: String, error: String) {}
}

object EmptyDownloadCatalog : DownloadCatalog {
    override fun completeEntries(): List<DownloadEntry> = emptyList()
    override fun record(itemId: String, episodeId: String): DownloadRecord? = null
    override fun isComplete(itemId: String, episodeId: String): Boolean = false
    override fun localUris(itemId: String, episodeId: String): List<String> = emptyList()
    override fun sidecar(itemId: String, episodeId: String): DownloadSidecar? = null
}

class MemoryDownloadCatalog : DownloadCatalog {
    private val rows = linkedMapOf<String, DownloadRecord>()
    private val sidecars = mutableMapOf<String, DownloadSidecar>()
    private val uris = mutableMapOf<String, List<String>>()
    private val sizes = mutableMapOf<String, Map<String, Long>>()

    fun put(
        record: DownloadRecord,
        sidecar: DownloadSidecar? = null,
        localUris: List<String> = emptyList(),
        fileSizes: Map<String, Long> = emptyMap(),
    ) {
        val key = catalogKey(record.itemId, record.episodeId)
        rows[key] = record
        if (sidecar != null) sidecars[key] = sidecar else sidecars.remove(key)
        uris[key] = localUris
        sizes[key] = fileSizes
    }

    override fun completeEntries(): List<DownloadEntry> {
        return rows.values.filter { it.complete }.map { it.toEntry() }
    }

    override fun record(itemId: String, episodeId: String): DownloadRecord? = rows[catalogKey(itemId, episodeId)]

    override fun isComplete(itemId: String, episodeId: String): Boolean {
        val key = catalogKey(itemId, episodeId)
        val row = rows[key] ?: return false
        if (row.status != DownloadStatus.COMPLETE) return false
        val sidecar = sidecars[key] ?: return false
        return DownloadPolicy.canPlayOffline(sidecar, sizes[key].orEmpty())
    }

    override fun localUris(itemId: String, episodeId: String): List<String> {
        if (!isComplete(itemId, episodeId)) return emptyList()
        return uris[catalogKey(itemId, episodeId)].orEmpty()
    }

    override fun sidecar(itemId: String, episodeId: String): DownloadSidecar? {
        return sidecars[catalogKey(itemId, episodeId)]
    }

    override fun markFailed(itemId: String, episodeId: String, error: String) {
        val key = catalogKey(itemId, episodeId)
        val row = rows[key] ?: return
        rows[key] = row.copy(status = DownloadStatus.FAILED, error = error)
    }

    companion object {
        fun catalogKey(itemId: String, episodeId: String): String = "$itemId\u0000$episodeId"
    }
}

object DownloadSidecarJson {
    fun encode(sidecar: DownloadSidecar): String = ApiClient.json.encodeToString(DownloadSidecar.serializer(), sidecar)

    fun decode(text: String): DownloadSidecar = ApiClient.json.decodeFromString(DownloadSidecar.serializer(), text)
}
