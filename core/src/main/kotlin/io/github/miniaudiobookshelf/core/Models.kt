package io.github.miniaudiobookshelf.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.Base64

data class Library(
    val id: String,
    val name: String = "",
    val mediaType: String = "",
)

data class MediaProgress(
    val id: String = "",
    val libraryItemId: String = "",
    val episodeId: String = "",
    val duration: Double = 0.0,
    val progress: Double = 0.0,
    val currentTime: Double = 0.0,
    val isFinished: Boolean = false,
)

data class AudioTrack(
    val index: Int = 0,
    val startOffset: Double = 0.0,
    val duration: Double = 0.0,
    val title: String = "",
    val contentUrl: String = "",
    val mimeType: String = "",
)

data class Chapter(
    val id: Int = 0,
    val start: Double = 0.0,
    val end: Double = 0.0,
    val title: String = "",
)

data class LibraryItem(
    val id: String,
    val libraryId: String = "",
    val mediaType: String = "",
    val title: String = "",
    val author: String = "",
    val seriesName: String = "",
    val seriesSequence: String = "",
    val episodeId: String = "",
    val duration: Double = 0.0,
    val publishedAt: Long = 0,
    val episodeIndex: Int = 0,
    val numTracks: Int = 0,
    val numEpisodes: Int = 0,
    val tracks: List<AudioTrack> = emptyList(),
    val chapters: List<Chapter> = emptyList(),
    val episodes: List<LibraryItem> = emptyList(),
    val progress: MediaProgress? = null,
) {
    val playable: Boolean
        get() = tracks.isNotEmpty() || numTracks > 0 || numEpisodes > 0 || episodeId.isNotEmpty()
    val hasChapters: Boolean get() = chapters.isNotEmpty()
    val hasEpisodes: Boolean get() = episodes.isNotEmpty()

    fun episodeById(id: String): LibraryItem? = episodes.firstOrNull { it.episodeId == id }
}

data class LibraryList(
    val libraries: List<Library>,
)

data class ItemPage(
    val results: List<LibraryItem>,
    val total: Int = 0,
    val limit: Int = 0,
    val page: Int = 0,
)

data class EpisodePage(
    val results: List<LibraryItem>,
    val total: Int = 0,
)

data class QueueEntry(
    val itemId: String,
    val episodeId: String = "",
    val title: String = "",
    val showTitle: String = "",
)

class PlaybackQueue {
    private val items = mutableListOf<QueueEntry>()

    @Synchronized
    fun snapshot(): List<QueueEntry> = items.toList()

    @Synchronized
    fun append(entry: QueueEntry) {
        if (entry.episodeId.isEmpty()) return
        if (contains(entry.itemId, entry.episodeId)) return
        items.add(entry)
    }

    @Synchronized
    fun contains(itemId: String, episodeId: String): Boolean {
        return items.any { it.itemId == itemId && it.episodeId == episodeId }
    }

    @Synchronized
    fun removeEpisode(itemId: String, episodeId: String) {
        items.removeAll { it.itemId == itemId && it.episodeId == episodeId }
    }

    @Synchronized
    fun clear() {
        items.clear()
    }
}

interface PlaybackStarter {
    fun play(item: LibraryItem, episodeId: String?, startOver: Boolean)
    fun playAt(item: LibraryItem, episodeId: String?, bookTime: Double) {}
    fun seekTo(bookTime: Double) {}
    fun skip(deltaSeconds: Double) {}
    fun stop() {}
    fun notifyLibraryChanged() {}
}

object Models {
    fun parseLibraryList(json: String): LibraryList {
        val root = objectRoot(json)
        val array = requireArray(root, "libraries")
        val libraries = array.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val id = requireString(obj, "id")
            Library(
                id = id,
                name = optString(obj, "name"),
                mediaType = optString(obj, "mediaType"),
            )
        }
        return LibraryList(libraries)
    }

    fun parseItemPage(json: String): ItemPage {
        val root = objectRoot(json)
        val array = requireArray(root, "results")
        return ItemPage(
            results = array.mapNotNull { (it as? JsonObject)?.let(::parseItemObject) },
            total = optInt(root, "total"),
            limit = optInt(root, "limit"),
            page = optInt(root, "page"),
        )
    }

    fun parseEpisodePage(json: String): EpisodePage {
        val root = objectRoot(json)
        val array = requireArray(root, "episodes")
        return EpisodePage(
            results = array.mapNotNull { (it as? JsonObject)?.let { o -> parseEpisode(o) } },
            total = optInt(root, "total"),
        )
    }

    fun parseItem(json: String): LibraryItem = parseItemObject(objectRoot(json))

    fun parsePlaySession(json: String): PlaySession {
        val root = objectRoot(json)
        val tracks = (root["audioTracks"] as? JsonArray)?.let(::readTracks).orEmpty()
        val chapters = (root["chapters"] as? JsonArray)?.let(::readChapters).orEmpty()
        val metadata = root["mediaMetadata"] as? JsonObject
        return PlaySession(
            id = requireString(root, "id"),
            libraryItemId = optString(root, "libraryItemId"),
            currentTime = optDouble(root, "currentTime"),
            duration = optDouble(root, "duration"),
            playMethod = optInt(root, "playMethod"),
            tracks = tracks,
            chapters = chapters,
            title = optString(root, "displayTitle").ifEmpty {
                metadata?.let { optString(it, "title") }.orEmpty()
            },
            author = optString(root, "displayAuthor").ifEmpty {
                metadata?.let { optString(it, "authorName") }.orEmpty()
            },
        )
    }

    fun parseItemObject(obj: JsonObject): LibraryItem {
        var progress: MediaProgress? = null
        for (field in listOf("userMediaProgress", "mediaProgress")) {
            val node = obj[field]
            if (node is JsonObject) {
                progress = parseProgress(node)
                break
            }
        }
        var item = LibraryItem(
            id = requireString(obj, "id"),
            libraryId = optString(obj, "libraryId"),
            mediaType = optString(obj, "mediaType"),
            progress = progress,
        )
        val media = obj["media"] as? JsonObject ?: return item
        val metadata = media["metadata"] as? JsonObject
        if (metadata != null) {
            val series = readSeries(metadata)
            item = item.copy(
                title = optString(metadata, "title"),
                author = optString(metadata, "authorName").ifEmpty { optString(metadata, "author") },
                seriesName = series.first,
                seriesSequence = series.second,
            )
        }
        var numTracks = optInt(media, "numTracks")
        var numEpisodes = optInt(media, "numEpisodes")
        val tracks = (media["tracks"] as? JsonArray)?.let(::readTracks).orEmpty()
        if (numTracks == 0 && tracks.isNotEmpty()) numTracks = tracks.size
        val chapters = (media["chapters"] as? JsonArray)?.let(::readChapters).orEmpty()
        val episodes = (media["episodes"] as? JsonArray)?.let { arr ->
            arr.mapNotNull { el -> (el as? JsonObject)?.let { parseEpisode(it, item.id) } }
        }.orEmpty()
        if (numEpisodes == 0 && episodes.isNotEmpty()) numEpisodes = episodes.size
        return item.copy(
            numTracks = numTracks,
            numEpisodes = numEpisodes,
            duration = optDouble(media, "duration"),
            tracks = tracks,
            chapters = chapters,
            episodes = episodes,
        )
    }

    fun parseEpisode(obj: JsonObject, libraryItemId: String = ""): LibraryItem {
        var item = LibraryItem(
            id = optString(obj, "libraryItemId", libraryItemId),
            libraryId = optString(obj, "libraryId"),
            mediaType = "podcast",
            episodeId = requireString(obj, "id"),
            title = optString(obj, "title").trim(),
            duration = optDouble(obj, "duration"),
            publishedAt = optLong(obj, "publishedAt"),
            episodeIndex = optInt(obj, "index"),
            numTracks = 1,
        )
        item = fillEpisodeFallbacks(obj, item)
        val podcast = obj["podcast"] as? JsonObject
        if (podcast != null) {
            if (item.id.isEmpty()) {
                item = item.copy(id = optString(podcast, "libraryItemId"))
            }
            if (item.libraryId.isEmpty()) {
                item = item.copy(libraryId = optString(podcast, "libraryId"))
            }
            val metadata = podcast["metadata"] as? JsonObject
            if (metadata != null) {
                var author = optString(metadata, "title")
                if (author.isEmpty()) author = optString(metadata, "authorName")
                if (author.isEmpty()) author = optString(metadata, "author")
                item = item.copy(author = author)
            }
        }
        var progress: MediaProgress? = null
        for (field in listOf("userMediaProgress", "mediaProgress", "progress")) {
            val node = obj[field]
            if (node is JsonObject) {
                progress = parseProgress(node)
                break
            }
        }
        return item.copy(progress = progress)
    }

    fun parseProgress(obj: JsonObject): MediaProgress {
        return MediaProgress(
            id = optString(obj, "id"),
            libraryItemId = optString(obj, "libraryItemId"),
            episodeId = optString(obj, "episodeId"),
            duration = optDouble(obj, "duration"),
            progress = optDouble(obj, "progress"),
            currentTime = optDouble(obj, "currentTime"),
            isFinished = optBool(obj, "isFinished"),
        )
    }

    fun searchHits(json: String, mediaType: String): List<LibraryItem> {
        val root = objectRoot(json)
        val array = root[mediaType] as? JsonArray ?: return emptyList()
        return array.mapNotNull { el ->
            val wrapper = el as? JsonObject ?: return@mapNotNull null
            val item = wrapper["libraryItem"] as? JsonObject ?: return@mapNotNull null
            parseItemObject(item)
        }
    }

    fun filterFragment(group: String, value: String): String {
        val b64 = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
        return "$group.${UriEscape.escape(b64)}"
    }

    fun inProgressUrlPath(libraryId: String, limit: Int): String {
        return "/api/libraries/${UriEscape.escape(libraryId)}/items?filter=${filterFragment("progress", "in-progress")}&sort=progress&desc=1&page=0&limit=$limit&minified=1"
    }

    fun recentEpisodesUrlPath(libraryId: String, page: Int = 0, limit: Int = 50): String {
        return "/api/libraries/${UriEscape.escape(libraryId)}/recent-episodes?limit=$limit&page=$page"
    }

    fun itemUrlPath(itemId: String, episodeId: String? = null): String {
        var path = "/api/items/${UriEscape.escape(itemId)}?expanded=1&include=progress"
        if (!episodeId.isNullOrEmpty()) {
            path += "&episode=${UriEscape.escape(episodeId)}"
        }
        return path
    }

    fun searchUrlPath(libraryId: String, query: String): String {
        return "/api/libraries/${UriEscape.escape(libraryId)}/search?q=${UriEscape.escape(query)}"
    }

    fun libraryItemsUrlPath(libraryId: String, page: Int = 0, limit: Int = 50): String {
        return "/api/libraries/${UriEscape.escape(libraryId)}/items?page=$page&limit=$limit&minified=1"
    }

    private fun readSeries(metadata: JsonObject): Pair<String, String> {
        var name = optString(metadata, "seriesName")
        var sequence = ""
        val seriesEl = metadata["series"]
        if (seriesEl is JsonArray) {
            for (el in seriesEl) {
                val series = el as? JsonObject ?: continue
                val seriesName = optString(series, "name")
                val seq = optSequence(series, "sequence")
                if (name.isEmpty() && seriesName.isNotEmpty()) name = seriesName
                if (seq.isEmpty()) continue
                sequence = seq
                if (seriesName.isNotEmpty()) name = seriesName
                return name to sequence
            }
        }
        val marker = ", #"
        val idx = name.lastIndexOf(marker)
        if (idx >= 0) {
            var seq = name.substring(idx + marker.length).trim()
            val comma = seq.indexOf(',')
            if (comma >= 0) seq = seq.substring(0, comma).trim()
            if (seq.isNotEmpty()) {
                sequence = seq
                name = name.substring(0, idx).trim()
            }
        }
        return name to sequence
    }

    private fun readTracks(array: JsonArray): List<AudioTrack> {
        return array.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            AudioTrack(
                index = optInt(obj, "index"),
                startOffset = optDouble(obj, "startOffset"),
                duration = optDouble(obj, "duration"),
                title = optString(obj, "title"),
                contentUrl = optString(obj, "contentUrl"),
                mimeType = optString(obj, "mimeType"),
            )
        }
    }

    private fun readChapters(array: JsonArray): List<Chapter> {
        return array.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            Chapter(
                id = optInt(obj, "id"),
                start = optDouble(obj, "start"),
                end = optDouble(obj, "end"),
                title = optString(obj, "title"),
            )
        }
    }

    private fun fillEpisodeFallbacks(obj: JsonObject, item: LibraryItem): LibraryItem {
        var title = item.title
        var duration = item.duration
        if (title.isEmpty()) title = optString(obj, "subtitle").trim()
        val audio = obj["audioFile"] as? JsonObject ?: return item.copy(title = title, duration = duration)
        if (duration == 0.0) duration = optDouble(audio, "duration")
        if (title.isEmpty()) {
            val tags = audio["metaTags"] as? JsonObject
            if (tags != null) title = optString(tags, "tagTitle").trim()
        }
        return item.copy(title = title, duration = duration)
    }

    private fun objectRoot(json: String): JsonObject {
        val el = try {
            ApiClient.json.parseToJsonElement(json)
        } catch (_: Exception) {
            throw ApiError.Server("malformed JSON")
        }
        return el as? JsonObject ?: throw ApiError.Server("expected object")
    }

    private fun requireArray(obj: JsonObject, field: String): JsonArray {
        return obj[field] as? JsonArray ?: throw ApiError.Server("missing field $field")
    }

    private fun requireString(obj: JsonObject, field: String): String {
        val p = obj[field] as? JsonPrimitive ?: throw ApiError.Server("missing field $field")
        return p.content
    }

    private fun optString(obj: JsonObject, field: String, fallback: String = ""): String {
        val el = obj[field] ?: return fallback
        if (el is JsonNull) return fallback
        val p = el as? JsonPrimitive ?: return fallback
        return p.contentOrNull ?: fallback
    }

    private fun optDouble(obj: JsonObject, field: String): Double {
        val p = obj[field] as? JsonPrimitive ?: return 0.0
        return p.doubleOrNull ?: p.contentOrNull?.toDoubleOrNull() ?: 0.0
    }

    private fun optInt(obj: JsonObject, field: String): Int {
        val p = obj[field] as? JsonPrimitive ?: return 0
        return p.longOrNull?.toInt() ?: p.contentOrNull?.toIntOrNull() ?: 0
    }

    private fun optLong(obj: JsonObject, field: String): Long {
        val p = obj[field] as? JsonPrimitive ?: return 0L
        return p.longOrNull ?: p.contentOrNull?.toLongOrNull() ?: 0L
    }

    private fun optBool(obj: JsonObject, field: String): Boolean {
        val p = obj[field] as? JsonPrimitive ?: return false
        return p.contentOrNull == "true" || p.content == "true"
    }

    private fun optSequence(obj: JsonObject, field: String): String {
        val p = obj[field] as? JsonPrimitive ?: return ""
        val asString = p.contentOrNull
        if (asString != null && p.isString) return asString.trim()
        val d = p.doubleOrNull ?: return (asString ?: "").trim()
        val whole = d.toLong()
        return if (d == whole.toDouble()) whole.toString() else d.toString()
    }
}
