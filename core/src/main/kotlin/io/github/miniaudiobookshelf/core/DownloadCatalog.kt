package io.github.miniaudiobookshelf.core

data class DownloadEntry(
    val itemId: String,
    val episodeId: String = "",
    val title: String = "",
    val author: String = "",
    val complete: Boolean = false,
)

interface DownloadCatalog {
    fun completeEntries(): List<DownloadEntry>
}

object EmptyDownloadCatalog : DownloadCatalog {
    override fun completeEntries(): List<DownloadEntry> = emptyList()
}
