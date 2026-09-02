package io.github.miniaudiobookshelf.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AutoBrowseTreeTest {
    @Test
    fun mediaIdsRoundTrip() {
        val ids = listOf(
            AutoMediaId.Root,
            AutoMediaId.Continue,
            AutoMediaId.Downloads,
            AutoMediaId.Libraries,
            AutoMediaId.Library("lib-books"),
            AutoMediaId.Item("item-1"),
            AutoMediaId.Item("item-1", "ep-9"),
        )
        for (id in ids) {
            assertEquals(id, AutoMediaId.parse(id.encode()))
        }
    }

    @Test
    fun sessionShapedIdsAreRejected() {
        assertTrue(AutoMediaId.looksLikeSession("/public/session/abc/track/0"))
        assertNull(AutoMediaId.parse("/public/session/abc/track/0"))
        assertNull(AutoMediaId.parse("item/x/public/session/abc"))
        assertNull(AutoMediaId.parse("root/libraries/http://host/public/session/x"))
        assertNull(AutoMediaId.parse(""))
        assertNull(AutoMediaId.parse("queue/1"))
    }

    @Test
    fun signedOutRootHasNoTabs() {
        assertTrue(AutoBrowseTree.rootTabs(signedIn = false, selected = null).isEmpty())
    }

    @Test
    fun signedInTabsFollowLibraryType() {
        val books = Library(id = "lib-b", name = "Books", mediaType = "book")
        val bookTabs = AutoBrowseTree.rootTabs(signedIn = true, selected = books)
        assertEquals(3, bookTabs.size)
        assertEquals("Continue", bookTabs[0].title)
        assertTrue(bookTabs.all { it.browsable && !it.playable })

        val pods = Library(id = "lib-p", name = "Shows", mediaType = "podcast")
        val podTabs = AutoBrowseTree.rootTabs(signedIn = true, selected = pods)
        assertEquals("Episodes", podTabs[0].title)
        assertEquals("Downloads", podTabs[1].title)
        assertEquals("Libraries", podTabs[2].title)
    }

    @Test
    fun continueDropsUnplayable() {
        val playable = LibraryItem(id = "a", title = "A", author = "Ada", numTracks = 1, mediaType = "book")
        val ebook = LibraryItem(id = "b", title = "Ebook", mediaType = "book")
        val nodes = AutoBrowseTree.continueChildren(listOf(playable, ebook))
        assertEquals(1, nodes.size)
        assertEquals("item/a", nodes[0].id.encode())
        assertTrue(nodes[0].playable)
        assertFalse(nodes[0].browsable)
    }

    @Test
    fun episodeNodeUsesEpisodeId() {
        val episode = LibraryItem(
            id = "show-1",
            episodeId = "ep-1",
            title = "Episode One",
            author = "The Show",
            mediaType = "podcast",
        )
        val node = AutoBrowseTree.itemNode(episode)!!
        assertEquals("item/show-1/ep/ep-1", node.id.encode())
        assertEquals("Episode One", node.title)
    }

    @Test
    fun downloadsOnlyComplete() {
        val entries = listOf(
            DownloadEntry(itemId = "a", title = "Partial", complete = false),
            DownloadEntry(itemId = "b", episodeId = "ep", title = "Done", author = "Ada", complete = true),
        )
        val nodes = AutoBrowseTree.downloadChildren(entries)
        assertEquals(1, nodes.size)
        assertEquals("item/b/ep/ep", nodes[0].id.encode())
        assertEquals("Done", nodes[0].title)
    }

    @Test
    fun libraryChildrenAreBrowsable() {
        val libraries = listOf(
            Library(id = "lib-b", name = "Main", mediaType = "book"),
            Library(id = "lib-p", name = "Pods", mediaType = "podcast"),
        )
        val nodes = AutoBrowseTree.libraryChildren(libraries)
        assertEquals("Books", nodes[0].subtitle)
        assertEquals("Podcasts", nodes[1].subtitle)
        assertTrue(nodes.all { it.browsable && !it.playable })
    }

    @Test
    fun pickSearchPlayableSkipsEbook() {
        val ebook = LibraryItem(id = "e", title = "Ebook")
        val book = LibraryItem(id = "b", title = "Book", numTracks = 2)
        assertEquals("b", AutoBrowseTree.pickSearchPlayable(listOf(ebook, book))!!.id)
        assertEquals("e", AutoBrowseTree.pickSearchPlayable(listOf(ebook))!!.id)
        assertNull(AutoBrowseTree.pickSearchPlayable(emptyList()))
    }
}

class AutoControllerAllowlistTest {
    @Test
    fun allowsAppAutoAssistantAndSystem() {
        val app = "io.github.miniaudiobookshelf.android"
        assertTrue(AutoControllerAllowlist.isAllowed(app, app))
        assertTrue(AutoControllerAllowlist.isAllowed("com.google.android.projection.gearhead", app))
        assertTrue(AutoControllerAllowlist.isAllowed("com.google.android.projection.gearhead:car", app))
        assertTrue(AutoControllerAllowlist.isAllowed("com.google.android.googlequicksearchbox", app))
        assertTrue(AutoControllerAllowlist.isAllowed("com.android.bluetooth", app))
        assertTrue(AutoControllerAllowlist.isAllowed("android", app))
        assertFalse(AutoControllerAllowlist.isAllowed("com.evil.browser", app))
        assertFalse(AutoControllerAllowlist.isAllowed("", app))
    }
}
