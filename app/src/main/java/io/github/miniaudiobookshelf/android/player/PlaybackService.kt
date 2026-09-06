package io.github.miniaudiobookshelf.android.player

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import io.github.miniaudiobookshelf.android.MainActivity
import io.github.miniaudiobookshelf.android.MiniAbsApplication
import io.github.miniaudiobookshelf.android.R
import io.github.miniaudiobookshelf.core.AutoBrowseTree
import io.github.miniaudiobookshelf.core.AutoControllerAllowlist
import io.github.miniaudiobookshelf.core.AutoMediaId
import io.github.miniaudiobookshelf.core.AutoNode
import io.github.miniaudiobookshelf.core.DownloadCatalog
import io.github.miniaudiobookshelf.core.Library
import io.github.miniaudiobookshelf.core.SettingsStore
import io.github.miniaudiobookshelf.core.SystemClock
import io.github.miniaudiobookshelf.core.ViewLogic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@UnstableApi
class PlaybackService : MediaLibraryService() {
    internal val serviceJob = SupervisorJob()
    internal val serviceScope = CoroutineScope(serviceJob + Dispatchers.Main.immediate)
    private var exo: ExoPlayer? = null
    private var bookPlayer: BookAwarePlayer? = null
    private var mediaSession: MediaLibrarySession? = null
    private var engine: PlaybackEngine? = null
    private var syncDriver: SyncDriver? = null
    private var artSizePx: Int = DEFAULT_ART_PX
    private var lastCoverUrl: String = ""
    private val coverLoader = CoverArtLoader { app().session.coverOkHttpClient() }
    private var carSessionGuard: CarSessionGuard? = null
    private var blockResumeAfterCar = false

    override fun onCreate() {
        super.onCreate()
        setShowNotificationForIdlePlayer(SHOW_NOTIFICATION_FOR_IDLE_PLAYER_NEVER)
        setMediaNotificationProvider(PlaybackNotificationProvider(this))
        val player = buildExoPlayer()
        val sessionPlayer = BookAwarePlayer(player)
        sessionPlayer.onPlayItem = { itemId, episodeId, startAt ->
            ensureEngine()?.playItem(itemId, episodeId, startAt)
        }
        sessionPlayer.onPlayFromSearch = { query ->
            serviceScope.launch { playFromSearch(query) }
        }
        sessionPlayer.allowResume = { allowResumeAfterCar() }
        val session = MediaLibrarySession.Builder(this, sessionPlayer, AutoLibraryCallback(this))
            .setSessionActivity(sessionActivity())
            .build()
        session.setMediaButtonPreferences(mediaButtons())
        session.setCustomLayout(mediaButtons())
        exo = player
        bookPlayer = sessionPlayer
        mediaSession = session
        val guard = CarSessionGuard(this) { pauseForCarDisconnect() }
        carSessionGuard = guard
        guard.start()
        app().playback.onLibraryInvalidated = { notifyTreeChanged() }
        serviceScope.launch {
            app().playback.state
                .map { NotificationSnapshot.from(it) }
                .distinctUntilChanged()
                .collectLatest { snap ->
                    setShowNotificationForIdlePlayer(
                        if (snap.itemId.isEmpty()) {
                            SHOW_NOTIFICATION_FOR_IDLE_PLAYER_NEVER
                        } else {
                            SHOW_NOTIFICATION_FOR_IDLE_PLAYER_ALWAYS
                        },
                    )
                    mediaSession?.setMediaButtonPreferences(mediaButtons())
                    mediaSession?.setCustomLayout(mediaButtons())
                    bookPlayer?.notifySession()
                    updateArtwork(snap)
                }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        if (!isControllerAllowed(controllerInfo)) {
            Log.w(
                AUTO_TAG,
                "reject session pkg=${controllerInfo.packageName} uid=${controllerInfo.uid} trusted=${controllerInfo.isTrusted}",
            )
            return null
        }
        Log.i(
            AUTO_TAG,
            "session pkg=${controllerInfo.packageName} uid=${controllerInfo.uid} trusted=${controllerInfo.isTrusted}",
        )
        return mediaSession
    }

    fun isControllerAllowed(controller: MediaSession.ControllerInfo): Boolean {
        if (controller.isTrusted) return true
        val session = mediaSession
        if (session != null &&
            (session.isAutoCompanionController(controller) || session.isAutomotiveController(controller))
        ) {
            return true
        }
        return AutoControllerAllowlist.isAllowed(controller.packageName, packageName)
    }

    fun isCarController(controller: MediaSession.ControllerInfo): Boolean {
        val session = mediaSession
        if (session != null &&
            (session.isAutoCompanionController(controller) || session.isAutomotiveController(controller))
        ) {
            return true
        }
        return AutoControllerAllowlist.isCarHost(controller.packageName)
    }

    fun noteCarController() {
        carSessionGuard?.markProjection()
        blockResumeAfterCar = false
    }

    fun pauseForCarDisconnect() {
        Log.i(AUTO_TAG, "car disconnected, pause")
        blockResumeAfterCar = true
        engine?.pause()
    }

    fun allowResumeAfterCar(): Boolean {
        if (!blockResumeAfterCar) return true
        val controller = mediaSession?.controllerForCurrentRequest
        if (controller != null && controller.packageName == packageName && !isCarController(controller)) {
            blockResumeAfterCar = false
            return true
        }
        Log.i(AUTO_TAG, "block resume pkg=${controller?.packageName}")
        return false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PLAY) {
            setShowNotificationForIdlePlayer(SHOW_NOTIFICATION_FOR_IDLE_PLAYER_ALWAYS)
        }
        val result = super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_PLAY -> {
                val itemId = intent.getStringExtra(EXTRA_ITEM_ID).orEmpty()
                if (itemId.isNotEmpty()) {
                    val episodeId = intent.getStringExtra(EXTRA_EPISODE_ID)?.ifEmpty { null }
                    val startAt = intent.getDoubleExtra(EXTRA_START_AT, -1.0)
                    ensureEngine()?.playItem(itemId, episodeId, startAt)
                }
            }
            ACTION_STOP -> {
                setShowNotificationForIdlePlayer(SHOW_NOTIFICATION_FOR_IDLE_PLAYER_NEVER)
                engine?.stop()
                stopSelf()
            }
        }
        return result
    }

    override fun onDestroy() {
        carSessionGuard?.stop()
        carSessionGuard = null
        app().playback.onLibraryInvalidated = null
        val currentEngine = engine
        val driver = syncDriver
        runBlocking {
            withTimeoutOrNull(4_000) {
                driver?.flushNow()
                currentEngine?.teardown()
            }
        }
        driver?.stop()
        currentEngine?.let { app().playback.detach(it) }
        currentEngine?.release()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        bookPlayer = null
        exo = null
        engine = null
        syncDriver = null
        serviceScope.cancel()
        super.onDestroy()
    }

    fun isSignedIn(): Boolean = app().session.isSignedIn()

    suspend fun awaitSessionSettled() {
        app().session.awaitSettled()
    }

    fun settings(): SettingsStore = app().session.settingsStore()

    fun lastPlayedNode(): AutoNode? {
        val settings = settings()
        return AutoBrowseTree.lastPlayed(
            itemId = settings.getLastItemId(),
            episodeId = settings.getLastEpisodeId(),
            title = settings.getLastTitle(),
            author = settings.getLastAuthor(),
        )
    }

    fun sendSignInError(session: MediaSession) {
        val extras = Bundle()
        extras.putString(AutoLibraryCallback.ERROR_RESOLUTION_ACTION_LABEL, getString(R.string.sign_in))
        extras.putParcelable(AutoLibraryCallback.ERROR_RESOLUTION_ACTION_INTENT, sessionActivity())
        session.setSessionExtras(extras)
        session.sendError(
            SessionError(
                SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED,
                getString(R.string.auto_sign_in_on_phone),
                extras,
            ),
        )
    }

    fun noteRootHints(params: LibraryParams?) {
        val size = params?.extras?.getInt(ART_SIZE_HINT, 0) ?: 0
        if (size > 0) {
            artSizePx = size
        }
    }

    suspend fun loadChildren(parentId: String): List<AutoNode> {
        val parsed = AutoMediaId.parseParent(parentId)
        if (parsed == AutoMediaId.Root) {
            return AutoBrowseTree.rootTabs()
        }
        app().session.awaitSettled()
        if (!isSignedIn()) {
            return AutoBrowseTree.notice("Sign in on your phone")
        }
        val api = app().session.apiClient() ?: return AutoBrowseTree.notice("Sign in on your phone")
        return withTimeoutOrNull(12_000) {
            when (parsed) {
                AutoMediaId.Root -> AutoBrowseTree.rootTabs()
                AutoMediaId.Continue -> homeNodes(selectedLibrary())
                AutoMediaId.Downloads -> AutoBrowseTree.downloadChildren(downloads().completeEntries())
                AutoMediaId.Libraries -> {
                    val list = withContext(Dispatchers.IO) { api.fetchLibraries() }
                    AutoBrowseTree.libraryChildren(list.libraries)
                }
                is AutoMediaId.Library -> {
                    val list = withContext(Dispatchers.IO) { api.fetchLibraries() }
                    val library = list.libraries.firstOrNull { it.id == parsed.libraryId }
                        ?: return@withTimeoutOrNull emptyList()
                    settings().setLibraryId(library.id)
                    notifyTreeChanged()
                    homeNodes(library)
                }
                AutoMediaId.SignIn -> emptyList()
                is AutoMediaId.Item -> emptyList()
            }
        } ?: AutoBrowseTree.notice("Couldn't load library")
    }

    suspend fun searchItems(query: String): List<MediaItem> {
        if (query.isBlank() || !isSignedIn()) return emptyList()
        val hits = searchHits(query)
        return hits.mapNotNull { item ->
            val node = AutoBrowseTree.itemNode(item) ?: return@mapNotNull null
            MediaItem.Builder()
                .setMediaId(node.id.encode())
                .setUri(node.id.playbackUri())
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(node.title)
                        .setSubtitle(node.subtitle.ifEmpty { null })
                        .setIsPlayable(true)
                        .setIsBrowsable(false)
                        .build(),
                )
                .build()
        }
    }

    suspend fun playFromSearch(query: String) {
        if (!isSignedIn()) return
        val api = app().session.apiClient() ?: return
        val hit = AutoBrowseTree.pickSearchPlayable(searchHits(query)) ?: return
        var episodeId = hit.episodeId.ifEmpty { null }
        var decisionItem = hit
        if (episodeId == null && (hit.mediaType == "podcast" || hit.hasEpisodes || hit.numEpisodes > 0)) {
            val expanded = withContext(Dispatchers.IO) { api.fetchItem(hit.id) }
            val episode = ViewLogic.firstUnfinishedEpisode(expanded)
            episodeId = episode?.episodeId
            if (episode != null) decisionItem = episode
        }
        val start = if (ViewLogic.playDecision(decisionItem) == ViewLogic.PlayDecision.START_OVER) 0.0 else -1.0
        ensureEngine()?.playItem(hit.id, episodeId, start)
    }

    fun skipSeconds(delta: Double) {
        ensureEngine()?.skip(delta)
    }

    fun cycleSpeed() {
        val next = ViewLogic.nextSpeed(app().playback.state.value.rate)
        ensureEngine()?.setRate(next)
    }

    internal fun ensureEngine(): PlaybackEngine? {
        engine?.let { return it }
        val api = app().session.apiClient() ?: return null
        val player = exo ?: return null
        val sessionPlayer = bookPlayer ?: return null
        val playback = PlaybackEngine(
            api = api,
            deviceId = app().session.deviceId(),
            player = player,
            settings = app().session.settingsStore(),
            scope = serviceScope,
            stateHolder = app().playback.stateFlow,
            mainHandler = Handler(Looper.getMainLooper()),
            catalog = app().downloads,
            reachability = app().reachability,
            localSessions = app().localSessions,
            localFlush = app().localFlush,
            signedIn = { app().session.isSignedIn() },
        )
        val driver = SyncDriver(
            engine = playback,
            api = api,
            clock = SystemClock(),
            scope = serviceScope,
            onConflict = { local, server -> app().playback.onSyncConflict(local, server) },
        )
        driver.start()
        sessionPlayer.engine = playback
        engine = playback
        syncDriver = driver
        app().playback.attach(playback)
        return playback
    }

    private suspend fun homeNodes(library: Library?): List<AutoNode> {
        if (library == null) return emptyList()
        val api = app().session.apiClient() ?: return emptyList()
        val items = withContext(Dispatchers.IO) {
            if (ViewLogic.isPodcastLibrary(library)) {
                api.fetchRecentEpisodes(library.id, 50).results
            } else {
                api.fetchInProgress(library.id, 25).results
            }
        }
        return AutoBrowseTree.continueChildren(items)
    }

    private suspend fun searchHits(query: String): List<io.github.miniaudiobookshelf.core.LibraryItem> {
        val api = app().session.apiClient() ?: return emptyList()
        val libraries = withContext(Dispatchers.IO) { api.fetchLibraries() }.libraries
        val selected = ViewLogic.pickLibrary(libraries, settings().getLibraryId())
        val order = buildList {
            if (selected != null) add(selected)
            libraries.filter { it.id != selected?.id }.forEach { add(it) }
        }
        for (library in order) {
            val json = withContext(Dispatchers.IO) { api.search(library.id, query) }
            val hits = ViewLogic.searchHits(json, ViewLogic.searchMediaKey(library))
            if (hits.isNotEmpty()) return hits
        }
        return emptyList()
    }

    private suspend fun selectedLibrary(): Library? {
        val api = app().session.apiClient() ?: return null
        val list = withContext(Dispatchers.IO) { api.fetchLibraries() }
        return ViewLogic.pickLibrary(list.libraries, settings().getLibraryId())
    }

    private fun notifyTreeChanged() {
        serviceScope.launch { notifyTreeChangedOnMain() }
    }

    private fun notifyTreeChangedOnMain() {
        val session = mediaSession ?: return
        session.notifyChildrenChanged(AutoMediaId.ROOT, Int.MAX_VALUE, null)
        session.notifyChildrenChanged(AutoMediaId.CONTINUE, Int.MAX_VALUE, null)
        session.notifyChildrenChanged(AutoMediaId.DOWNLOADS, Int.MAX_VALUE, null)
        session.notifyChildrenChanged(AutoMediaId.LIBRARIES, Int.MAX_VALUE, null)
        if (!isSignedIn()) {
            sendSignInError(session)
        }
    }

    private suspend fun updateArtwork(snap: NotificationSnapshot) {
        if (snap.itemId.isEmpty()) {
            lastCoverUrl = ""
            bookPlayer?.artworkData = null
            return
        }
        val url = snap.artUrl
        if (url.isEmpty() || url == lastCoverUrl) return
        lastCoverUrl = url
        val bytes = withContext(Dispatchers.IO) {
            try {
                coverLoader.load(url, artSizePx)
            } catch (_: Exception) {
                null
            }
        }
        if (bytes != null) {
            bookPlayer?.artworkData = bytes
        }
    }

    private fun mediaButtons(): List<CommandButton> {
        val rate = app().playback.state.value.rate
        return listOf(
            CommandButton.Builder(CommandButton.ICON_SKIP_BACK_10)
                .setSessionCommand(SessionCommand(ACTION_SKIP_BACK_10, Bundle.EMPTY))
                .setDisplayName(getString(R.string.skip_back_10))
                .setSlots(CommandButton.SLOT_BACK)
                .build(),
            CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_10)
                .setSessionCommand(SessionCommand(ACTION_SKIP_FORWARD_10, Bundle.EMPTY))
                .setDisplayName(getString(R.string.skip_forward_10))
                .setSlots(CommandButton.SLOT_FORWARD)
                .build(),
            CommandButton.Builder(speedIcon(rate))
                .setSessionCommand(SessionCommand(ACTION_CYCLE_SPEED, Bundle.EMPTY))
                .setDisplayName(ViewLogic.formatRate(rate))
                .setSlots(
                    CommandButton.SLOT_FORWARD_SECONDARY,
                    CommandButton.SLOT_BACK_SECONDARY,
                    CommandButton.SLOT_OVERFLOW,
                )
                .build(),
        )
    }

    private fun speedIcon(rate: Double): Int {
        return when {
            rate < 0.65 -> CommandButton.ICON_PLAYBACK_SPEED_0_5
            rate < 0.9 -> CommandButton.ICON_PLAYBACK_SPEED_0_8
            rate < 1.1 -> CommandButton.ICON_PLAYBACK_SPEED_1_0
            rate < 1.35 -> CommandButton.ICON_PLAYBACK_SPEED_1_2
            rate < 1.65 -> CommandButton.ICON_PLAYBACK_SPEED_1_5
            rate < 1.9 -> CommandButton.ICON_PLAYBACK_SPEED_1_8
            else -> CommandButton.ICON_PLAYBACK_SPEED_2_0
        }
    }

    private fun buildExoPlayer(): ExoPlayer {
        val mediaClient = app().session.mediaOkHttpClient()
        val httpDataSourceFactory = OkHttpDataSource.Factory(mediaClient)
        val dataSourceFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(dataSourceFactory))
            .setSeekBackIncrementMs(30_000)
            .setSeekForwardIncrementMs(30_000)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
            .build()
        return player
    }

    private fun sessionActivity(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(this, 0, intent, flags)
    }

    private fun downloads(): DownloadCatalog = app().session.downloads

    private fun app(): MiniAbsApplication = application as MiniAbsApplication

    companion object {
        const val ACTION_PLAY = "io.github.miniaudiobookshelf.android.action.PLAY"
        const val ACTION_STOP = "io.github.miniaudiobookshelf.android.action.STOP"
        const val EXTRA_ITEM_ID = "item_id"
        const val EXTRA_EPISODE_ID = "episode_id"
        const val EXTRA_START_AT = "start_at"
        const val ACTION_SKIP_BACK_10 = "io.github.miniaudiobookshelf.android.action.SKIP_BACK_10"
        const val ACTION_SKIP_FORWARD_10 = "io.github.miniaudiobookshelf.android.action.SKIP_FORWARD_10"
        const val ACTION_CYCLE_SPEED = "io.github.miniaudiobookshelf.android.action.CYCLE_SPEED"
        const val AUTO_TAG = "MiniAbsAuto"
        private const val DEFAULT_ART_PX = 256
        private const val ART_SIZE_HINT = "android.media.extras.MEDIA_ART_SIZE_HINT_PIXELS"
    }
}

private data class NotificationSnapshot(
    val itemId: String,
    val title: String,
    val author: String,
    val intended: IntendedState,
    val artUrl: String,
    val rate: Double,
) {
    companion object {
        fun from(state: PlayerState) = NotificationSnapshot(
            itemId = state.itemId,
            title = state.title,
            author = state.author,
            intended = state.intended,
            artUrl = state.artUrl,
            rate = state.rate,
        )
    }
}
