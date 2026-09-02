package io.github.miniaudiobookshelf.core

data class PlaySession(
    val id: String,
    val libraryItemId: String = "",
    val currentTime: Double = 0.0,
    val duration: Double = 0.0,
    val playMethod: Int = 0,
    val tracks: List<AudioTrack> = emptyList(),
    val chapters: List<Chapter> = emptyList(),
    val title: String = "",
    val author: String = "",
) {
    val isDirectPlay: Boolean get() = playMethod == PLAY_METHOD_DIRECT && tracks.isNotEmpty()

    companion object {
        const val PLAY_METHOD_DIRECT = 0

        fun parse(json: String): PlaySession = Models.parsePlaySession(json)
    }
}

sealed class PlaybackError(message: String) : Exception(message) {
    class TranscodeOnly : PlaybackError("This item can only be transcoded, which Mini Audiobookshelf does not support.")
    class NoSession : PlaybackError("No playback session.")
    class Pipeline(message: String = "The player could not start.") : PlaybackError(message)
}
