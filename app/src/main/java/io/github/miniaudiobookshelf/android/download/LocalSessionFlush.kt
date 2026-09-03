package io.github.miniaudiobookshelf.android.download

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import io.github.miniaudiobookshelf.core.ApiClient
import io.github.miniaudiobookshelf.core.ApiError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class LocalSessionFlush(
    private val store: LocalSessionStore,
    private val api: () -> ApiClient?,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    var afterFlush: suspend () -> Unit = {}

    fun flushAsync() {
        scope.launch { flush() }
    }

    suspend fun flush() {
        mutex.withLock {
            val pending = store.unsynced()
            if (pending.isNotEmpty()) {
                val client = api() ?: return@withLock
                val results = try {
                    withContext(Dispatchers.IO) { client.syncLocalSessions(pending) }
                } catch (_: ApiError) {
                    emptyList()
                } catch (_: Exception) {
                    emptyList()
                }
                if (results.isNotEmpty()) {
                    val ok = results.filter { it.success }.map { it.id }.toSet()
                    store.markSynced(ok)
                }
            }
        }
        afterFlush()
    }
}

class ServerReachability(
    private val connectivity: ConnectivityManager,
    private val api: () -> ApiClient?,
) {
    suspend fun isReachable(): Boolean {
        val network = connectivity.activeNetwork ?: return false
        val caps = connectivity.getNetworkCapabilities(network) ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        val client = api() ?: return false
        return try {
            withContext(Dispatchers.IO) { client.probeStatus() }
            true
        } catch (_: Exception) {
            false
        }
    }
}

class NetworkFlushWatcher(
    private val connectivity: ConnectivityManager,
    private val flush: LocalSessionFlush,
) {
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            flush.flushAsync()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                flush.flushAsync()
            }
        }
    }

    fun start() {
        connectivity.registerDefaultNetworkCallback(callback)
    }
}
