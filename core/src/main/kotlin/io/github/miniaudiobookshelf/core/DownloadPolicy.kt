package io.github.miniaudiobookshelf.core

object DownloadPolicy {
    enum class PlayMode {
        LIVE_LOCAL,
        LIVE_STREAM,
        OFFLINE_LOCAL,
        CANNOT_PLAY,
    }

    fun canPlayOffline(sidecar: DownloadSidecar?, fileSizes: Map<String, Long>): Boolean {
        if (sidecar == null || sidecar.tracks.isEmpty()) return false
        for (track in sidecar.tracks) {
            val size = fileSizes[track.fileName] ?: return false
            if (size <= 0L) return false
            if (track.byteSize > 0L && size != track.byteSize) return false
        }
        return true
    }

    fun playMode(complete: Boolean, serverReachable: Boolean): PlayMode {
        return when {
            serverReachable && complete -> PlayMode.LIVE_LOCAL
            serverReachable && !complete -> PlayMode.LIVE_STREAM
            !serverReachable && complete -> PlayMode.OFFLINE_LOCAL
            else -> PlayMode.CANNOT_PLAY
        }
    }

    fun canDownload(canDownloadPermission: Boolean, hasFiles: Boolean): Boolean {
        return canDownloadPermission && hasFiles
    }
}

object TrackUriResolver {
    fun playlistUris(complete: Boolean, localUris: List<String>, streamUris: List<String>): List<String> {
        if (complete && localUris.isNotEmpty()) return localUris
        return streamUris
    }
}

object DownloadFiles {
    fun inoFromContentUrl(contentUrl: String): String {
        val path = contentUrl.substringBefore('?').trimEnd('/')
        val marker = "/file/"
        val at = path.lastIndexOf(marker)
        if (at < 0) return ""
        return path.substring(at + marker.length)
    }

    fun fileDownloadPath(itemId: String, ino: String): String {
        return "/api/items/${UriEscape.escape(itemId)}/file/${UriEscape.escape(ino)}/download"
    }

    fun relativeDir(itemId: String, episodeId: String): String {
        return if (episodeId.isEmpty()) itemId else "${itemId}_$episodeId"
    }

    fun audioFileName(index: Int): String = index.toString()
}
