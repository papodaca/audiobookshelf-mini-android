package io.github.miniaudiobookshelf.android

import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.os.Bundle
import androidx.room.Room
import coil.Coil
import coil.ImageLoader
import io.github.miniaudiobookshelf.android.download.DownloadController
import io.github.miniaudiobookshelf.android.download.DownloadDatabase
import io.github.miniaudiobookshelf.android.download.DownloadRepository
import io.github.miniaudiobookshelf.android.download.LocalSessionFlush
import io.github.miniaudiobookshelf.android.download.LocalSessionStore
import io.github.miniaudiobookshelf.android.download.NetworkFlushWatcher
import io.github.miniaudiobookshelf.android.download.ServerReachability
import io.github.miniaudiobookshelf.android.player.PlaybackCommands
import io.github.miniaudiobookshelf.android.session.SessionController
import io.github.miniaudiobookshelf.android.store.DataStoreSettingsStore
import io.github.miniaudiobookshelf.android.store.EncryptedSecretStore
import io.github.miniaudiobookshelf.core.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MiniAbsApplication : Application() {
    lateinit var session: SessionController
        private set
    lateinit var playback: PlaybackCommands
        private set
    lateinit var downloads: DownloadRepository
        private set
    lateinit var downloadController: DownloadController
        private set
    lateinit var localSessions: LocalSessionStore
        private set
    lateinit var localFlush: LocalSessionFlush
        private set
    lateinit var reachability: ServerReachability
        private set

    var foregroundActivities: Int = 0
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(ForegroundWatcher())
        val settings = DataStoreSettingsStore(this)
        val secrets = EncryptedSecretStore.create(this)
        val db = Room.databaseBuilder(this, DownloadDatabase::class.java, "mabs-downloads")
            .addMigrations(DownloadDatabase.MIGRATION_1_2)
            .build()
        downloads = DownloadRepository(this, db.dao())
        localSessions = LocalSessionStore(this)
        playback = PlaybackCommands(this)
        session = SessionController(settings, secrets, SystemClock(), playback)
        session.downloads = downloads
        downloadController = DownloadController(
            context = this,
            repository = downloads,
            settings = settings,
            scope = appScope,
            canDownload = { session.canDownload() },
        )
        val connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        localFlush = LocalSessionFlush(localSessions, { session.apiClient() }, appScope)
        localFlush.afterFlush = { refreshDownloadProgress() }
        reachability = ServerReachability(connectivity, { session.apiClient() })
        NetworkFlushWatcher(connectivity, localFlush).start()
        session.onSignOutDownloads = { delete ->
            if (delete) downloadController.deleteAll()
        }
        session.onSignedIn = {
            localFlush.flushAsync()
            val api = session.apiClient()
            if (api != null) {
                downloadController.backfillCovers(session.coverOkHttpClient()) { api.coverUrl(it) }
            }
        }
        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .okHttpClient { session.coverOkHttpClient() }
                .crossfade(true)
                .build(),
        )
        session.start()
        watchQueueAdvance()
        watchDownloads()
        appScope.launch(Dispatchers.IO) { downloads.hydrate() }
    }

    private suspend fun refreshDownloadProgress() {
        if (!session.isSignedIn()) return
        val api = session.apiClient() ?: return
        downloadController.refreshProgress(api, localSessions)
    }

    private fun watchDownloads() {
        appScope.launch {
            downloads.records
                .map { rows -> rows.filter { it.complete }.map { it.itemId to it.episodeId } }
                .distinctUntilChanged()
                .collect { playback.notifyLibraryChanged() }
        }
    }

    private fun watchQueueAdvance() {
        appScope.launch {
            playback.state
                .map { it.finished }
                .distinctUntilChanged()
                .collect { finished ->
                    if (!finished) return@collect
                    if (!session.playNextQueued()) {
                        playback.acknowledgeFinished()
                    }
                }
        }
    }

    private inner class ForegroundWatcher : ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {
            foregroundActivities += 1
        }
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {
            foregroundActivities = (foregroundActivities - 1).coerceAtLeast(0)
        }
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }
}
