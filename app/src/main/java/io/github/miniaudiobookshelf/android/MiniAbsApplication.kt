package io.github.miniaudiobookshelf.android

import android.app.Application
import io.github.miniaudiobookshelf.android.session.SessionController
import io.github.miniaudiobookshelf.android.store.DataStoreSettingsStore
import io.github.miniaudiobookshelf.android.store.EncryptedSecretStore
import io.github.miniaudiobookshelf.core.SystemClock

class MiniAbsApplication : Application() {
    lateinit var session: SessionController
        private set

    override fun onCreate() {
        super.onCreate()
        val settings = DataStoreSettingsStore(this)
        val secrets = EncryptedSecretStore.create(this)
        session = SessionController(settings, secrets, SystemClock())
        session.start()
    }
}
