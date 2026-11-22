package io.github.miniaudiobookshelf.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private fun fixture(name: String): String {
    return ModelsTest::class.java.classLoader!!.getResource("fixtures/$name")!!.readText()
}

class ModelsTest {
    @Test
    fun librariesParseBookAndPodcast() {
        val list = Models.parseLibraryList(fixture("libraries.json"))
        assertEquals(2, list.libraries.size)
        assertEquals("lib-books", list.libraries[0].id)
        assertEquals("book", list.libraries[0].mediaType)
        assertEquals("lib-pods", list.libraries[1].id)
        assertEquals("podcast", list.libraries[1].mediaType)
    }

    @Test
    fun itemPageSeriesBadgeAndUnplayableEbook() {
        val page = Models.parseItemPage(fixture("item-page.json"))
        assertEquals(2, page.results.size)
        val book = page.results[0]
        assertEquals("The Single File Book", book.title)
        assertEquals("Ada Author", book.author)
        assertEquals("2", book.seriesSequence)
        assertEquals("#2", ViewLogic.seriesBadge(book))
        assertTrue(book.playable)
        val ebook = page.results[1]
        assertFalse(ebook.playable)
        assertEquals("Ebook Only", ebook.title)
    }

    @Test
    fun inProgressDropsPodcastsViaBookItems() {
        val page = Models.parseItemPage(fixture("items-in-progress.json"))
        val books = ViewLogic.bookItems(page.results)
        assertEquals(1, books.size)
        assertEquals("item-in-progress", books[0].id)
        assertEquals("book", books[0].mediaType)
    }

    @Test
    fun expandedBookHasChaptersAndIgnoresUnknownKeys() {
        val item = Models.parseItem(fixture("item-single-file.json"))
        assertEquals("item-single", item.id)
        assertEquals(2, item.chapters.size)
        assertEquals("Chapter One", item.chapters[0].title)
        assertEquals(3600.0, item.duration)
        assertTrue(item.playable)
        assertEquals("123", item.tracks[0].ino)
        assertEquals(12345678L, item.tracks[0].byteSize)
    }

    @Test
    fun noAudioIsUnplayable() {
        val item = Models.parseItem(fixture("item-no-audio.json"))
        assertFalse(item.playable)
        assertEquals(ViewLogic.PlayDecision.NOT_PLAYABLE, ViewLogic.playDecision(item))
    }

    @Test
    fun podcastEpisodesAndFinishedProgress() {
        val show = Models.parseItem(fixture("item-podcast.json"))
        assertEquals("podcast", show.mediaType)
        assertEquals(2, show.episodes.size)
        assertEquals("Episode One", show.episodes[0].title)
        assertTrue(show.episodes[1].progress!!.isFinished)
        val first = ViewLogic.firstUnfinishedEpisode(show)
        assertEquals("ep-1", first!!.episodeId)
    }

    @Test
    fun userMediaProgressPreferredOverMediaProgress() {
        val json = """
            {
              "id": "item-x",
              "libraryId": "lib-books",
              "mediaType": "book",
              "userMediaProgress": {
                "currentTime": 900,
                "duration": 3600,
                "progress": 0.25,
                "isFinished": false
              },
              "mediaProgress": {
                "currentTime": 1,
                "duration": 3600,
                "progress": 0.01,
                "isFinished": true
              },
              "media": { "metadata": { "title": "X" }, "numTracks": 1 }
            }
        """.trimIndent()
        val item = Models.parseItem(json)
        assertEquals(900.0, item.progress!!.currentTime)
        assertFalse(item.progress!!.isFinished)
        assertEquals("25% · 45m left", ViewLogic.progressSummary(item))
    }

    @Test
    fun seriesNameStringFallback() {
        val json = """
            {
              "id": "item-s",
              "mediaType": "book",
              "media": {
                "metadata": { "title": "Caliban's War", "seriesName": "Expanse, #2" },
                "numTracks": 1
              }
            }
        """.trimIndent()
        val item = Models.parseItem(json)
        assertEquals("Expanse", item.seriesName)
        assertEquals("2", item.seriesSequence)
        assertEquals("#2", ViewLogic.seriesBadge(item))
    }

    @Test
    fun searchHitsAreTyped() {
        val json = fixture("search.json")
        val books = ViewLogic.searchHits(json, "book")
        val pods = ViewLogic.searchHits(json, "podcast")
        assertEquals(1, books.size)
        assertEquals("item-single", books[0].id)
        assertEquals(1, pods.size)
        assertEquals("item-podcast", pods[0].id)
    }

    @Test
    fun recentEpisodesParseAmpersandReadyTitle() {
        val page = Models.parseEpisodePage(fixture("recent-episodes.json"))
        assertEquals(1, page.results.size)
        assertEquals("Episode One", ViewLogic.episodeLabel(page.results[0]))
        val ampersand = Models.parseEpisode(
            kotlinx.serialization.json.Json.parseToJsonElement(
                """{"id":"ep-x","libraryItemId":"show","title":"Foo & Bar","duration":60}""",
            ) as kotlinx.serialization.json.JsonObject,
        )
        assertEquals("Foo & Bar", ViewLogic.episodeLabel(ampersand))
    }

    @Test
    fun playDecisionFinishedIsStartOver() {
        val item = LibraryItem(
            id = "i",
            numTracks = 1,
            progress = MediaProgress(currentTime = 3600.0, duration = 3600.0, progress = 1.0, isFinished = true),
        )
        assertEquals(ViewLogic.PlayDecision.START_OVER, ViewLogic.playDecision(item))
        val unstarted = LibraryItem(id = "j", numTracks = 1)
        assertEquals(ViewLogic.PlayDecision.START, ViewLogic.playDecision(unstarted))
        val mid = LibraryItem(
            id = "k",
            numTracks = 1,
            progress = MediaProgress(currentTime = 1800.0, duration = 7200.0, progress = 0.25),
        )
        assertEquals(ViewLogic.PlayDecision.RESUME, ViewLogic.playDecision(mid))
        assertEquals("25% · 1h 30m left", ViewLogic.progressSummary(mid))
    }

    @Test
    fun queueAppendsUniqueEpisodes() {
        val queue = PlaybackQueue()
        val entry = QueueEntry(itemId = "show", episodeId = "ep-1", title = "Foo & Bar", showTitle = "Show")
        queue.append(entry)
        queue.append(entry)
        queue.append(QueueEntry(itemId = "show", episodeId = "", title = "nope"))
        assertEquals(listOf(entry), queue.snapshot())
    }

    @Test
    fun pickLibraryRestoresSavedId() {
        val books = Library("lib-books", "Books", "book")
        val pods = Library("lib-pods", "Podcasts", "podcast")
        assertEquals(pods, ViewLogic.pickLibrary(listOf(books, pods), "lib-pods"))
        assertEquals(books, ViewLogic.pickLibrary(listOf(books, pods), "gone"))
        assertNull(ViewLogic.pickLibrary(emptyList(), "lib-books"))
    }

    @Test
    fun progressListParsesArrayAndLastUpdate() {
        val json = """
            [
              {"libraryItemId":"item-1","episodeId":"","currentTime":90,"duration":300,"progress":0.3,"isFinished":false,"lastUpdate":1700000000000},
              {"libraryItemId":"show","episodeId":"ep-1","currentTime":15,"duration":60,"isFinished":false}
            ]
        """.trimIndent()
        val rows = Models.parseProgressList(json)
        assertEquals(2, rows.size)
        assertEquals("item-1", rows[0].libraryItemId)
        assertEquals(90.0, rows[0].currentTime)
        assertEquals(1_700_000_000_000L, rows[0].lastUpdate)
        assertEquals("ep-1", rows[1].episodeId)
        val wrapped = Models.parseProgressList("""{"mediaProgress":[{"libraryItemId":"item-2","currentTime":8}]}""")
        assertEquals(1, wrapped.size)
        assertEquals("item-2", wrapped[0].libraryItemId)
        assertEquals(8.0, wrapped[0].currentTime)
    }

    @Test
    fun attachProgressFillsMissingByItemAndEpisode() {
        val book = LibraryItem(id = "item-1")
        val episode = LibraryItem(id = "show", episodeId = "ep-1", mediaType = "podcast")
        val untouched = LibraryItem(id = "item-2")
        val already = LibraryItem(
            id = "item-3",
            progress = MediaProgress(libraryItemId = "item-3", currentTime = 5.0, duration = 50.0),
        )
        val all = listOf(
            MediaProgress(libraryItemId = "item-1", currentTime = 90.0, duration = 300.0, progress = 0.3),
            MediaProgress(libraryItemId = "show", episodeId = "ep-1", currentTime = 15.0, duration = 60.0, progress = 0.25),
            MediaProgress(libraryItemId = "item-3", currentTime = 99.0, duration = 100.0, progress = 0.99),
        )
        val out = Models.attachProgress(listOf(book, episode, untouched, already), all)
        assertEquals(90.0, out[0].progress?.currentTime)
        assertEquals(15.0, out[1].progress?.currentTime)
        assertNull(out[2].progress)
        // Existing progress is never overwritten.
        assertEquals(5.0, out[3].progress?.currentTime)
    }
}