package io.github.miniaudiobookshelf.android.session

import io.github.miniaudiobookshelf.android.browse.BrowseController
import io.github.miniaudiobookshelf.android.http.OkHttpTransport
import io.github.miniaudiobookshelf.core.ApiClient
import io.github.miniaudiobookshelf.core.ApiError
import io.github.miniaudiobookshelf.core.AuthOffer
import io.github.miniaudiobookshelf.core.Clock
import io.github.miniaudiobookshelf.core.ConnectDecision
import io.github.miniaudiobookshelf.core.ConnectFailure
import io.github.miniaudiobookshelf.core.CredentialManager
import io.github.miniaudiobookshelf.core.CredentialMode
import io.github.miniaudiobookshelf.core.DownloadCatalog
import io.github.miniaudiobookshelf.core.EmptyDownloadCatalog
import io.github.miniaudiobookshelf.core.LibraryItem
import io.github.miniaudiobookshelf.core.PlaybackQueue
import io.github.miniaudiobookshelf.core.PlaybackStarter
import io.github.miniaudiobookshelf.core.ServerUrl
import io.github.miniaudiobookshelf.core.SettingsStore
import io.github.miniaudiobookshelf.core.TokenStore
import io.github.miniaudiobookshelf.core.TrustStore
import io.github.miniaudiobookshelf.core.ViewLogic
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SessionController(
    private val settings: SettingsStore,
    private val tokenStore: TokenStore,
    private val clock: Clock,
    private val playback: PlaybackStarter,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val transport = OkHttpTransport(settings)
    private val trust = TrustStore(settings)

    private var api: ApiClient? = null
    private var credentials: CredentialManager? = null
    private var server: ServerUrl? = null
    private var browse: BrowseController? = null
    private val queue = PlaybackQueue()
    var downloads: DownloadCatalog = EmptyDownloadCatalog

    fun coverOkHttpClient(): okhttp3.OkHttpClient = transport.imageClient { credentials }

    fun mediaOkHttpClient(): okhttp3.OkHttpClient = transport.mediaClient()

    fun apiClient(): ApiClient? = api

    fun settingsStore(): SettingsStore = settings

    fun deviceId(): String {
        val mgr = credentials
        if (mgr != null) return mgr.ensureDeviceId()
        if (settings.getDeviceId().isEmpty()) {
            settings.setDeviceId(java.util.UUID.randomUUID().toString())
        }
        return settings.getDeviceId()
    }

    private val _state = MutableStateFlow<SessionState>(SessionState.Loading)
    val state: StateFlow<SessionState> = _state

    fun start() {
        ensureDeviceId()
        scope.launch {
            withContext(Dispatchers.IO) { restore() }
        }
    }

    fun probe(urlText: String, httpAck: Boolean) {
        scope.launch {
            withContext(Dispatchers.IO) { probeInternal(urlText, httpAck) }
        }
    }

    fun pinCertificate() {
        val connect = currentConnect() ?: return
        val pin = connect.pin ?: return
        trust.pin(pin.origin, pin.pem)
        transport.rebuild()
        _state.value = SessionState.Connect(connect.copy(pin = null, error = null))
        probe(connect.url, httpAck = true)
    }

    fun refusePin() {
        _state.update { state ->
            if (state is SessionState.Connect) {
                SessionState.Connect(
                    state.ui.copy(
                        pin = null,
                        offer = null,
                        error = ViewLogic.userMessage(ConnectFailure.TLS_UNTRUSTED),
                    ),
                )
            } else {
                state
            }
        }
    }

    fun signInPassword(username: String, password: String) {
        scope.launch {
            val mgr = credentials ?: return@launch
            setBusy(true)
            try {
                withContext(Dispatchers.IO) { mgr.signInPassword(username, password) }
                api?.setTokenProvider(mgr)
                showSignedIn()
            } catch (e: Exception) {
                showConnectError(ViewLogic.classifyApiError(e, apiKeyPath = false))
            }
        }
    }

    fun signInApiKey(key: String) {
        scope.launch {
            val mgr = credentials ?: return@launch
            setBusy(true)
            try {
                withContext(Dispatchers.IO) { mgr.signInApiKey(key) }
                api?.setTokenProvider(mgr)
                showSignedIn()
            } catch (e: Exception) {
                showConnectError(ViewLogic.classifyApiError(e, apiKeyPath = true))
            }
        }
    }

    fun confirmSignOut() {
        scope.launch {
            playback.stop()
            try {
                withContext(Dispatchers.IO) { credentials?.signOut() }
            } catch (_: Exception) {
            }
            val url = server?.normalized ?: settings.getServerUrl()
            settings.setLastItemId("")
            settings.setLastEpisodeId("")
            queue.clear()
            browse = null
            playback.notifyLibraryChanged()
            _state.value = SessionState.Connect(ConnectUi(url = url))
        }
    }

    fun applyDebugLaunch(url: String, token: String, itemId: String, pinPem: String?) {
        if (url.isEmpty() || token.isEmpty() || itemId.isEmpty()) return
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val parsed = ServerUrl.parse(url)
                    if (!pinPem.isNullOrEmpty()) {
                        trust.pin(parsed.origin, pinPem)
                    }
                    rebuild(parsed)
                    val mgr = credentials ?: return@withContext
                    mgr.signInApiKey(token)
                    api?.setTokenProvider(mgr)
                }
                if (api != null && credentials != null) {
                    showSignedIn()
                    playback.play(
                        LibraryItem(id = itemId),
                        episodeId = null,
                        startOver = false,
                    )
                }
            } catch (_: Exception) {
                showConnectError(ViewLogic.classifyApiError(ApiError.Unauthorized(), apiKeyPath = true), url)
            }
        }
    }

    fun dismissToast() {
        _state.update { state ->
            if (state is SessionState.SignedIn) state.copy(toast = null) else state
        }
    }

    private suspend fun restore() {
        val url = settings.getServerUrl()
        if (url.isEmpty()) {
            _state.value = SessionState.Connect(ConnectUi())
            return
        }
        try {
            val parsed = ServerUrl.parse(url)
            rebuild(parsed)
            val mgr = credentials
            if (mgr != null && mgr.restore()) {
                api?.setTokenProvider(mgr)
                showSignedIn()
                return
            }
        } catch (_: Exception) {
        }
        _state.value = SessionState.Connect(ConnectUi(url = url))
    }

    private suspend fun probeInternal(urlText: String, httpAck: Boolean) {
        setBusy(true)
        try {
            val parsed = ServerUrl.parse(urlText)
            if (parsed.isHttp) {
                if (!httpAck) {
                    _state.value = SessionState.Connect(
                        ConnectUi(
                            url = urlText,
                            showHttpWarning = true,
                            httpAck = false,
                            busy = false,
                        ),
                    )
                    return
                }
                settings.setHttpAcknowledged(parsed.origin, true)
            }
            rebuild(parsed)
            val status = api!!.probeStatus()
            if (ViewLogic.versionDecision(status.version) == ConnectDecision.TOO_OLD) {
                showConnectError(ConnectFailure.TOO_OLD, urlText, parsed.isHttp, httpAck)
                return
            }
            val offer = ViewLogic.authOffer(status)
            _state.value = SessionState.Connect(
                ConnectUi(
                    url = urlText,
                    showHttpWarning = parsed.isHttp,
                    httpAck = httpAck || !parsed.isHttp,
                    offer = offer,
                    busy = false,
                ),
            )
        } catch (e: ApiError.Tls) {
            handleTls(urlText, httpAck)
        } catch (e: ApiError) {
            val parsed = try {
                ServerUrl.parse(urlText)
            } catch (_: ApiError) {
                null
            }
            showConnectError(
                ViewLogic.classifyApiError(e, apiKeyPath = false),
                urlText,
                parsed?.isHttp == true,
                httpAck,
            )
        } catch (_: Exception) {
            showConnectError(ConnectFailure.UNREACHABLE, urlText, httpAck = httpAck)
        }
    }

    private fun handleTls(urlText: String, httpAck: Boolean) {
        val parsed = server
        val pem = transport.lastPeerPem
        val alreadyPinned = parsed != null && trust.pemFor(parsed.origin) != null
        if (alreadyPinned || pem.isEmpty()) {
            showConnectError(ConnectFailure.TLS_UNTRUSTED, urlText, parsed?.isHttp == true, httpAck)
            return
        }
        val fingerprint = sha256Hex(pem)
        val body = ViewLogic.userMessage(ConnectFailure.TLS_UNTRUSTED) + "\n\nSHA-256: " + fingerprint
        _state.value = SessionState.Connect(
            ConnectUi(
                url = urlText,
                showHttpWarning = parsed?.isHttp == true,
                httpAck = httpAck,
                error = body,
                pin = PinPrompt(origin = parsed?.origin.orEmpty(), fingerprint = fingerprint, pem = pem),
                busy = false,
            ),
        )
    }

    private fun rebuild(parsed: ServerUrl) {
        settings.setServerUrl(parsed.normalized)
        transport.rebuild()
        val client = ApiClient(parsed, transport, clock = clock)
        val mgr = CredentialManager(client, tokenStore, settings, clock, parsed.origin)
        client.setTokenProvider(mgr)
        api = client
        credentials = mgr
        server = parsed
    }

    private fun showSignedIn() {
        val mgr = credentials ?: return
        val toast = if (mgr.consumeDegradedNotice()) {
            "You'll need to sign in again next time"
        } else {
            null
        }
        val client = api ?: return
        val controller = BrowseController(
            api = client,
            settings = settings,
            queue = queue,
            playback = playback,
            scope = scope,
            onSessionExpired = { confirmSignOut() },
        )
        browse = controller
        controller.start()
        _state.value = SessionState.SignedIn(
            origin = server?.origin.orEmpty(),
            apiKey = mgr.mode == CredentialMode.API_KEY,
            toast = toast,
            browse = controller,
        )
        playback.notifyLibraryChanged()
    }

    private fun showConnectError(
        failure: ConnectFailure,
        urlText: String = currentConnect()?.url.orEmpty(),
        showHttp: Boolean = currentConnect()?.showHttpWarning == true,
        httpAck: Boolean = currentConnect()?.httpAck == true,
    ) {
        _state.value = SessionState.Connect(
            ConnectUi(
                url = urlText,
                showHttpWarning = showHttp,
                httpAck = httpAck,
                error = ViewLogic.userMessage(failure),
                busy = false,
            ),
        )
    }

    private fun setBusy(busy: Boolean) {
        _state.update { state ->
            when (state) {
                is SessionState.Connect -> SessionState.Connect(state.ui.copy(busy = busy, error = if (busy) null else state.ui.error))
                else -> state
            }
        }
    }

    private fun currentConnect(): ConnectUi? = (_state.value as? SessionState.Connect)?.ui

    private fun ensureDeviceId() {
        val mgr = credentials
        if (mgr != null) {
            mgr.ensureDeviceId()
            return
        }
        if (settings.getDeviceId().isEmpty()) {
            settings.setDeviceId(java.util.UUID.randomUUID().toString())
        }
    }
}

sealed class SessionState {
    data object Loading : SessionState()
    data class Connect(val ui: ConnectUi) : SessionState()
    data class SignedIn(
        val origin: String,
        val apiKey: Boolean,
        val toast: String? = null,
        val browse: BrowseController,
    ) : SessionState()
}

data class ConnectUi(
    val url: String = "",
    val showHttpWarning: Boolean = false,
    val httpAck: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val offer: AuthOffer? = null,
    val pin: PinPrompt? = null,
)

data class PinPrompt(
    val origin: String,
    val fingerprint: String,
    val pem: String,
)

internal fun sha256Hex(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { b -> "%02x".format(b) }
}
