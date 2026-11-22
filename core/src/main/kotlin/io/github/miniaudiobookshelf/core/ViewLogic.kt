package io.github.miniaudiobookshelf.core

enum class ConnectDecision {
    PROCEED,
    TOO_OLD,
}

enum class ConnectFailure {
    UNREACHABLE,
    NOT_ABS,
    TOO_OLD,
    BAD_PASSWORD,
    REJECTED_API_KEY,
    RATE_LIMITED,
    HTTP_WARNING,
    TLS_UNTRUSTED,
}

data class AuthOffer(
    val password: Boolean,
    val apiKey: Boolean,
)

object ViewLogic {
    fun authOffer(status: ServerStatus): AuthOffer {
        return AuthOffer(password = status.localAuth, apiKey = true)
    }

    fun versionDecision(serverVersion: String): ConnectDecision {
        return if (ServerVersion.isSupported(serverVersion)) {
            ConnectDecision.PROCEED
        } else {
            ConnectDecision.TOO_OLD
        }
    }

    fun messageId(failure: ConnectFailure): String {
        return when (failure) {
            ConnectFailure.UNREACHABLE -> "connect.unreachable"
            ConnectFailure.NOT_ABS -> "connect.not-abs"
            ConnectFailure.TOO_OLD -> "connect.too-old"
            ConnectFailure.BAD_PASSWORD -> "connect.bad-password"
            ConnectFailure.REJECTED_API_KEY -> "connect.rejected-api-key"
            ConnectFailure.RATE_LIMITED -> "connect.rate-limited"
            ConnectFailure.HTTP_WARNING -> "connect.http-warning"
            ConnectFailure.TLS_UNTRUSTED -> "connect.tls-untrusted"
        }
    }

    fun userMessage(failure: ConnectFailure): String {
        return when (failure) {
            ConnectFailure.UNREACHABLE -> "The server could not be reached."
            ConnectFailure.NOT_ABS -> "That URL does not look like an Audiobookshelf server."
            ConnectFailure.TOO_OLD -> "This server is older than 2.26.0 and is not supported."
            ConnectFailure.BAD_PASSWORD -> "The username or password was rejected."
            ConnectFailure.REJECTED_API_KEY ->
                "The API key was rejected. It may be revoked or expired; an admin can issue a new one in the server UI."
            ConnectFailure.RATE_LIMITED ->
                "Too many sign-in attempts. Wait for the countdown before trying again."
            ConnectFailure.HTTP_WARNING ->
                "This address is not HTTPS. Your password and access token will travel unencrypted, and audio and cover art can be altered in transit by anyone on the path."
            ConnectFailure.TLS_UNTRUSTED ->
                "The server certificate could not be verified. You can pin this exact certificate for this origin, or refuse the connection."
        }
    }

    fun classifyApiError(error: Throwable, apiKeyPath: Boolean): ConnectFailure {
        return when (error) {
            is ApiError.ServerTooOld -> ConnectFailure.TOO_OLD
            is ApiError.Unauthorized -> if (apiKeyPath) {
                ConnectFailure.REJECTED_API_KEY
            } else {
                ConnectFailure.BAD_PASSWORD
            }
            is ApiError.RateLimited -> ConnectFailure.RATE_LIMITED
            is ApiError.Tls -> ConnectFailure.TLS_UNTRUSTED
            is ApiError.InvalidUrl, is ApiError.NotFound, is ApiError.Server -> ConnectFailure.NOT_ABS
            else -> ConnectFailure.UNREACHABLE
        }
    }

    fun defaultAuthMode(offer: AuthOffer, remembered: AuthMode?): AuthMode {
        if (!offer.password) return AuthMode.API_KEY
        if (remembered == AuthMode.API_KEY) return AuthMode.API_KEY
        return AuthMode.PASSWORD
    }

    fun canSubmit(mode: AuthMode, username: String, password: String, apiKey: String): Boolean {
        return when (mode) {
            AuthMode.PASSWORD -> username.isNotBlank() && password.isNotBlank()
            AuthMode.API_KEY -> apiKey.isNotBlank()
        }
    }

    fun signInFailureStays(failure: ConnectFailure): Boolean {
        return when (failure) {
            ConnectFailure.BAD_PASSWORD,
            ConnectFailure.REJECTED_API_KEY,
            ConnectFailure.RATE_LIMITED,
            -> true
            else -> false
        }
    }

    fun probeAdvances(failure: ConnectFailure): Boolean {
        return failure == ConnectFailure.UNREACHABLE || failure == ConnectFailure.TLS_UNTRUSTED
    }

    fun probeCandidates(input: String): List<ServerUrl> {
        val parsed = ServerUrl.parse(input)
        if (!parsed.schemeLess) return listOf(parsed)
        val host = parsed.normalized
        return listOf(
            ServerUrl.parse("https://$host"),
            ServerUrl.parse("http://$host"),
        )
    }

    enum class PlayDecision {
        START,
        RESUME,
        START_OVER,
        NOT_PLAYABLE,
    }

    fun playDecision(item: LibraryItem): PlayDecision {
        if (!item.playable) return PlayDecision.NOT_PLAYABLE
        val progress = item.progress
        if (progress != null && progress.isFinished) return PlayDecision.START_OVER
        if (progress != null && progress.currentTime > 0) return PlayDecision.RESUME
        return PlayDecision.START
    }

    fun playLabel(decision: PlayDecision): String {
        return when (decision) {
            PlayDecision.START -> "Play"
            PlayDecision.RESUME -> "Resume"
            PlayDecision.START_OVER -> "Start over"
            PlayDecision.NOT_PLAYABLE -> "No playable audio"
        }
    }

    fun unplayableExplanation(): String = "This item has no playable audio."

    fun seriesBadge(item: LibraryItem): String? {
        val sequence = item.seriesSequence.trim()
        if (sequence.isEmpty()) return null
        return if (sequence.startsWith("#")) sequence else "#$sequence"
    }

    fun formatClock(seconds: Double): String {
        var total = seconds
        if (total < 0) total = 0.0
        val whole = (total + 0.0000001).toInt()
        val h = whole / 3600
        val m = (whole % 3600) / 60
        val s = whole % 60
        return if (h > 0) {
            "%d:%02d:%02d".format(h, m, s)
        } else {
            "%d:%02d".format(m, s)
        }
    }

    fun formatSpan(seconds: Double): String {
        val total = if (seconds > 0) seconds.toInt() else 0
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m"
            else -> "under a minute"
        }
    }

    fun progressFraction(item: LibraryItem): Double {
        val progress = item.progress ?: return 0.0
        if (progress.isFinished) return 1.0
        var fraction = progress.progress
        if (fraction <= 0 && progress.duration > 0) {
            fraction = progress.currentTime / progress.duration
        }
        return fraction.coerceIn(0.0, 1.0)
    }

    fun progressSummary(item: LibraryItem): String? {
        val progress = item.progress ?: return null
        if (progress.isFinished) return "Finished"
        if (progress.currentTime <= 0) return null
        val percent = (progressFraction(item) * 100 + 0.5).toInt()
        val remaining = progress.duration - progress.currentTime
        if (progress.duration <= 0 || remaining <= 0) {
            return "$percent% complete"
        }
        return "$percent% · ${formatSpan(remaining)} left"
    }

    fun bookLibraries(list: LibraryList): List<Library> = list.libraries.filter { it.mediaType == "book" }

    fun bookItems(items: List<LibraryItem>): List<LibraryItem> = items.filter { it.mediaType == "book" }

    fun pickLibrary(libraries: List<Library>, savedId: String): Library? {
        if (libraries.isEmpty()) return null
        if (savedId.isNotEmpty()) {
            libraries.firstOrNull { it.id == savedId }?.let { return it }
        }
        return libraries.first()
    }

    fun isPodcastLibrary(library: Library?): Boolean = library?.mediaType == "podcast"

    fun searchMediaKey(library: Library?): String = if (isPodcastLibrary(library)) "podcast" else "book"

    fun firstUnfinishedIn(episodes: List<LibraryItem>): LibraryItem? {
        return episodes.firstOrNull { it.progress == null || !it.progress.isFinished } ?: episodes.firstOrNull()
    }

    fun firstUnfinishedEpisode(show: LibraryItem): LibraryItem? = firstUnfinishedIn(show.episodes)

    fun episodeLabel(episode: LibraryItem): String {
        val title = episode.title.trim()
        if (title.isNotEmpty()) return title
        if (episode.episodeIndex > 0) return "Episode ${episode.episodeIndex}"
        return "Untitled episode"
    }

    fun episodeSubtitle(episode: LibraryItem): String {
        progressSummary(episode)?.let { return it }
        if (episode.duration > 0) return formatClock(episode.duration)
        return ""
    }

    fun sortedEpisodes(episodes: List<LibraryItem>, newestFirst: Boolean): List<LibraryItem> {
        val cmp = compareBy<LibraryItem> { it.publishedAt }
            .thenBy { it.episodeIndex }
            .thenBy { it.episodeId }
        val sorted = episodes.sortedWith(cmp)
        return if (newestFirst) sorted.reversed() else sorted
    }

    fun searchHits(json: String, mediaType: String): List<LibraryItem> = Models.searchHits(json, mediaType)

    fun clampRate(rate: Double): Double = rate.coerceIn(0.5, 3.0)

    val speedPresets: List<Double> = listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0, 2.5, 3.0)

    fun nextSpeed(current: Double): Double {
        val clamped = clampRate(current)
        return speedPresets.firstOrNull { it > clamped + 0.01 } ?: speedPresets.first()
    }

    fun controllerStartOverride(startPositionMs: Long): Double {
        if (startPositionMs <= 0L) return -1.0
        return startPositionMs / 1000.0
    }

    fun ignoreControllerResetSeek(currentSeconds: Double, seekSeconds: Double): Boolean {
        return seekSeconds <= 0.5 && currentSeconds > 5.0
    }

    fun formatRate(rate: Double): String {
        val clamped = clampRate(rate)
        return if (clamped == clamped.toInt().toDouble()) {
            "${clamped.toInt()}×"
        } else {
            "$clamped×"
        }
    }

    /** Human-readable byte count, shared by the download rows and their total. */
    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.1f MB", mb)
        return String.format("%.2f GB", mb / 1024.0)
    }

    /** Total on-disk size of completed downloads only; in-progress bytes don't count. */
    fun completedDownloadBytes(records: List<DownloadRecord>): Long {
        return records.filter { it.complete }.sumOf { it.bytesDownloaded }
    }
}
