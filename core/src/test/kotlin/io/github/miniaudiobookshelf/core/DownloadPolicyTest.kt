package io.github.miniaudiobookshelf.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DownloadPolicyTest {
    private val sidecar = DownloadSidecar(
        itemId = "item-1",
        title = "Book",
        tracks = listOf(
            SidecarTrack(index = 1, startOffset = 0.0, duration = 10.0, fileName = "1", byteSize = 100L, ino = "ino-1"),
            SidecarTrack(index = 2, startOffset = 10.0, duration = 10.0, fileName = "2", byteSize = 200L, ino = "ino-2"),
        ),
    )

    @Test
    fun missingSidecarIsNotComplete() {
        assertFalse(DownloadPolicy.canPlayOffline(null, mapOf("1" to 100L)))
    }

    @Test
    fun missingFileIsNotPlayable() {
        assertFalse(DownloadPolicy.canPlayOffline(sidecar, mapOf("1" to 100L)))
    }

    @Test
    fun sizeMismatchIsNotPlayable() {
        assertFalse(DownloadPolicy.canPlayOffline(sidecar, mapOf("1" to 100L, "2" to 199L)))
    }

    @Test
    fun matchingSizesArePlayable() {
        assertTrue(DownloadPolicy.canPlayOffline(sidecar, mapOf("1" to 100L, "2" to 200L)))
    }

    @Test
    fun catalogCompleteRequiresSidecarAndSizes() {
        val catalog = MemoryDownloadCatalog()
        catalog.put(
            DownloadRecord(itemId = "item-1", status = DownloadStatus.COMPLETE, sidecarPath = "sidecar.json"),
            sidecar = sidecar,
            localUris = listOf("file:///a/1", "file:///a/2"),
            fileSizes = mapOf("1" to 100L, "2" to 50L),
        )
        assertFalse(catalog.isComplete("item-1", ""))
        assertTrue(catalog.localUris("item-1", "").isEmpty())
    }

    @Test
    fun completeCatalogReturnsAllLocalUris() {
        val catalog = MemoryDownloadCatalog()
        catalog.put(
            DownloadRecord(itemId = "item-1", status = DownloadStatus.COMPLETE),
            sidecar = sidecar,
            localUris = listOf("file:///a/1", "file:///a/2"),
            fileSizes = mapOf("1" to 100L, "2" to 200L),
        )
        assertTrue(catalog.isComplete("item-1", ""))
        assertEquals(listOf("file:///a/1", "file:///a/2"), catalog.localUris("item-1", ""))
        assertEquals(1, catalog.completeEntries().size)
    }

    @Test
    fun playModeNeverMixes() {
        assertEquals(DownloadPolicy.PlayMode.LIVE_LOCAL, DownloadPolicy.playMode(complete = true, serverReachable = true))
        assertEquals(DownloadPolicy.PlayMode.LIVE_STREAM, DownloadPolicy.playMode(complete = false, serverReachable = true))
        assertEquals(DownloadPolicy.PlayMode.OFFLINE_LOCAL, DownloadPolicy.playMode(complete = true, serverReachable = false))
        assertEquals(DownloadPolicy.PlayMode.CANNOT_PLAY, DownloadPolicy.playMode(complete = false, serverReachable = false))
    }

    @Test
    fun resolverUsesAllLocalOrAllStream() {
        val local = listOf("file:///a/1", "file:///a/2")
        val stream = listOf("https://abs/t/0", "https://abs/t/1")
        assertEquals(local, TrackUriResolver.playlistUris(complete = true, localUris = local, streamUris = stream))
        assertEquals(stream, TrackUriResolver.playlistUris(complete = false, localUris = local, streamUris = stream))
        assertEquals(stream, TrackUriResolver.playlistUris(complete = true, localUris = emptyList(), streamUris = stream))
    }

    @Test
    fun downloadPathUsesInoAndNoQueryToken() {
        val path = DownloadFiles.fileDownloadPath("item-1", "123")
        assertEquals("/api/items/item-1/file/123/download", path)
        assertFalse(path.contains("token="))
        assertEquals("123", DownloadFiles.inoFromContentUrl("/api/items/item-single/file/123"))
        assertEquals("123", DownloadFiles.inoFromContentUrl("/api/items/item-single/file/123?token=secret"))
    }

    @Test
    fun hideDownloadWithoutPermissionOrFiles() {
        assertFalse(DownloadPolicy.canDownload(canDownloadPermission = false, hasFiles = true))
        assertFalse(DownloadPolicy.canDownload(canDownloadPermission = true, hasFiles = false))
        assertTrue(DownloadPolicy.canDownload(canDownloadPermission = true, hasFiles = true))
    }
}

class LocalSessionCodecTest {
    @Test
    fun newIdsAreUuidV4() {
        val id = LocalPlaybackSession.newId()
        assertTrue(LocalPlaybackSession.isUuidV4(id))
        assertFalse(LocalPlaybackSession.isUuidV4("play_local_abc"))
    }

    @Test
    fun requestBodyIsSessionsArrayWithLocalPlayMethod() {
        val session = LocalPlaybackSession(
            id = "11111111-1111-4111-8111-111111111111",
            libraryItemId = "item-1",
            duration = 60.0,
            currentTime = 12.0,
            timeListening = 12.0,
            startedAt = 1_700_000_000_000L,
            updatedAt = 1_700_000_720_000L,
            displayTitle = "Book",
            deviceInfo = LocalDeviceInfo(deviceId = "dev-1"),
        )
        val body = LocalSessionCodec.requestBody(listOf(session))
        assertTrue(body.contains("\"sessions\""))
        assertTrue(body.contains("\"playMethod\":3"))
        assertTrue(body.contains("11111111-1111-4111-8111-111111111111"))
        assertFalse(body.contains("play_local_"))
    }

    @Test
    fun parsePerSessionResults() {
        val json = """{"results":[{"id":"a","success":true},{"id":"b","success":false,"error":"Media item not found"}]}"""
        val results = LocalSessionCodec.parseResults(json)
        assertEquals(2, results.size)
        assertTrue(results[0].success)
        assertFalse(results[1].success)
        assertEquals("Media item not found", results[1].error)
    }
}

class UserAccessParserTest {
    @Test
    fun downloadFalseHidesControls() {
        val json = """{"user":{"id":"u1","token":"t","permissions":{"download":false}}}"""
        val access = UserAccessParser.parse(json)
        assertEquals("u1", access.userId)
        assertFalse(access.canDownload)
    }

    @Test
    fun missingPermissionsDefaultToDownloadAllowed() {
        val json = """{"user":{"id":"u1","token":"t"}}"""
        assertTrue(UserAccessParser.parse(json).canDownload)
    }
}
