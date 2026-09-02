package io.github.miniaudiobookshelf.android

import android.app.Activity
import android.app.Application
import android.os.Bundle
import coil.Coil
import coil.ImageLoader
import io.github.miniaudiobookshelf.android.player.PlaybackCommands
import io.github.miniaudiobookshelf.android.session.SessionController
import io.github.miniaudiobookshelf.android.store.DataStoreSettingsStore
import io.github.miniaudiobookshelf.android.store.EncryptedSecretStore
import io.github.miniaudiobookshelf.core.SystemClock

class MiniAbsApplication : Application() {
    lateinit var session: SessionController
        private set
    lateinit var playback: PlaybackCommands
        private set

    var foregroundActivities: Int = 0
        private set

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(ForegroundWatcher())
        val settings = DataStoreSettingsStore(this)
        val secrets = EncryptedSecretStore.create(this)
        playback = PlaybackCommands(this)
        session = SessionController(settings, secrets, SystemClock(), playback)
        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .okHttpClient { session.coverOkHttpClient() }
                .crossfade(true)
                .build(),
        )
        session.start()
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
