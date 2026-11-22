package io.github.miniaudiobookshelf.android.session

import io.github.miniaudiobookshelf.android.browse.BrowseController
import io.github.miniaudiobookshelf.android.http.OkHttpTransport
import io.github.miniaudiobookshelf.core.ApiClient
import io.github.miniaudiobookshelf.core.ApiError
import io.github.miniaudiobookshelf.core.AuthMode
import io.github.miniaudiobookshelf.core.Clock
import io.github.miniaudiobookshelf.core.ConnectDecision
import io.github.miniaudiobookshelf.core.ConnectEvent
import io.github.miniaudiobookshelf.core.ConnectFailure
import io.github.miniaudiobookshelf.core.ConnectFlow
import io.github.miniaudiobookshelf.core.ConnectReason
import io.github.miniaudiobookshelf.core.ConnectStep
import io.github.miniaudiobookshelf.core.CredentialManager
import io.github.miniaudiobookshelf.core.CredentialMode
import io.github.miniaudiobookshelf.core.DownloadCatalog
import io.github.miniaudiobookshelf.core.EmptyDownloadCatalog
import io.github.miniaudiobookshelf.core.LibraryItem
import io.github.miniaudiobookshelf.core.PinPrompt
import io.github.miniaudiobookshelf.core.PlaybackQueue
import io.github.miniaudiobookshelf.core.PlaybackStarter
import io.github.miniaudiobookshelf.core.QueueCodec
import io.github.miniaudiobookshelf.core.ServerUrl
import io.github.miniaudiobookshelf.core.SettingsStore
import io.github.miniaudiobookshelf.core.TokenProvider
import io.github.miniaudiobookshelf.core.TokenStore
import io.github.miniaudiobookshelf.core.TrustStore
import io.github.miniaudiobookshelf.core.UserAccess
import io.github.miniaudiobookshelf.core.UserAccessParser
import io.github.miniaudiobookshelf.core.ViewLogic
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

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
    private var connectReason: ConnectReason = ConnectReason.NONE
    private val queue = PlaybackQueue()
    var downloads: DownloadCatalog = EmptyDownloadCatalog

    init {
        // Persist the Up next queue so it survives process death: restore
        // what was stored, then write through on every queue change.
        // Sign-out clears the queue, which persists as empty the same way.
        queue.restore(QueueCodec.decode(settings.getQueueEntries()))
        scope.launch {
            queue.entries.collect { entries ->
                settings.setQueueEntries(QueueCodec.encode(entries))
            }
        }
    }
    var onSignOutDownloads: ((delete: Boolean) -> Unit)? = null
    var onSignedIn: (() -> Unit)? = null
    var userAccess: UserAccess = UserAccess()
        private set

    fun coverOkHttpClient(): okhttp3.OkHttpClient = transport.imageClient { credentials }

    fun mediaOkHttpClient(): okhttp3.OkHttpClient = transport.mediaClient()

    fun downloadOkHttpClient(): okhttp3.OkHttpClient = transport.mediaClient()

    fun apiClient(): ApiClient? = api

    fun tokenProvider(): TokenProvider? = credentials

    fun settingsStore(): SettingsStore = settings

    fun isSignedIn(): Boolean = _state.value is SessionState.SignedIn

    suspend fun awaitSettled(timeoutMs: Long = 8_000): SessionState {
        val current = _state.value
        if (current !is SessionState.Loading) return current
        return withTimeoutOrNull(timeoutMs) {
            state.first { it !is SessionState.Loading }
        } ?: _state.value
    }

    fun canDownload(): Boolean = isSignedIn() && userAccess.canDownload

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
            val restored = try {
                withContext(Dispatchers.IO) {
                    withTimeout(20_000) { restoreCredentials() }
                }
            } catch (_: TimeoutCancellationException) {
                false
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            if (restored) {
                showSignedIn()
            } else {
                enterConnect(ConnectReason.RESTORE_FAILED)
            }
        }
    }

    fun probe(urlText: String, httpAck: Boolean) {
        scope.launch {
            withContext(Dispatchers.IO) { probeInternal(urlText, httpAck) }
        }
    }

    fun changeServer() {
        connectReason = ConnectReason.NONE
        apply(ConnectEvent.ChangeServer)
    }

    fun switchMode(mode: AuthMode) {
        apply(ConnectEvent.SwitchMode(mode))
    }

    fun pinCertificate() {
        val trustStep = currentStep() as? ConnectStep.Trust ?: return
        trust.pin(trustStep.pin.origin, trustStep.pin.pem)
        transport.rebuild()
        apply(ConnectEvent.PinAccepted)
        probe(trustStep.server.url, httpAck = true)
    }

    fun refusePin() {
        apply(ConnectEvent.PinRefused)
    }

    fun signInPassword(username: String, password: String) {
        scope.launch {
            val creds = currentStep() as? ConnectStep.Credentials ?: return@launch
            if (creds.server.origin != server?.origin) return@launch
            val mgr = credentials ?: return@launch
            apply(ConnectEvent.SignInStarted)
            try {
                withContext(Dispatchers.IO) {
                    mgr.signInPassword(username, password)
                    api?.setTokenProvider(mgr)
                    refreshAccess()
                }
                settings.setLastUsername(creds.server.origin, username)
                settings.setLastAuthMode(creds.server.origin, AuthMode.PASSWORD)
                showSignedIn()
            } catch (e: Exception) {
                apply(ConnectEvent.SignInFailed(ViewLogic.classifyApiError(e, apiKeyPath = false)))
            }
        }
    }

    fun signInApiKey(key: String) {
        scope.launch {
            val creds = currentStep() as? ConnectStep.Credentials ?: return@launch
            if (creds.server.origin != server?.origin) return@launch
            val mgr = credentials ?: return@launch
            apply(ConnectEvent.SignInStarted)
            try {
                withContext(Dispatchers.IO) {
                    mgr.signInApiKey(key)
                    api?.setTokenProvider(mgr)
                    refreshAccess()
                }
                settings.setLastAuthMode(creds.server.origin, AuthMode.API_KEY)
                showSignedIn()
            } catch (e: Exception) {
                apply(ConnectEvent.SignInFailed(ViewLogic.classifyApiError(e, apiKeyPath = true)))
            }
        }
    }

    fun confirmSignOut(deleteDownloads: Boolean = false) {
        scope.launch {
            playback.stop()
            try {
                withContext(Dispatchers.IO) { credentials?.signOut(logoutOnServer = true) }
            } catch (_: Exception) {
            }
            onSignOutDownloads?.invoke(deleteDownloads)
            settings.setLastItemId("")
            settings.setLastEpisodeId("")
            settings.setLastTitle("")
            settings.setLastAuthor("")
            queue.clear()
            browse = null
            userAccess = UserAccess()
            playback.notifyLibraryChanged()
            enterConnect(ConnectReason.SIGNED_OUT)
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
                    refreshAccess()
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
                enterConnect(ConnectReason.RESTORE_FAILED)
                apply(
                    ConnectEvent.ProbeFailed(
                        url = url,
                        message = ViewLogic.userMessage(
                            ViewLogic.classifyApiError(ApiError.Unauthorized(), apiKeyPath = true),
                        ),
                    ),
                )
            }
        }
    }

    fun dismissToast() {
        _state.update { state ->
            if (state is SessionState.SignedIn) state.copy(toast = null) else state
        }
    }

    fun playNextQueued(): Boolean {
        return browse?.playNextQueued() == true
    }

    private fun expireSession() {
        scope.launch {
            playback.stop()
            try {
                withContext(Dispatchers.IO) { credentials?.signOut(logoutOnServer = false) }
            } catch (_: Exception) {
            }
            queue.clear()
            browse = null
            userAccess = UserAccess()
            playback.notifyLibraryChanged()
            enterConnect(ConnectReason.SESSION_EXPIRED)
        }
    }

    private fun enterConnect(reason: ConnectReason) {
        connectReason = if (reason == ConnectReason.RESTORE_FAILED && settings.getServerUrl().isEmpty()) {
            ConnectReason.NONE
        } else {
            reason
        }
        val url = settings.getServerUrl()
        if (url.isEmpty()) {
            _state.value = SessionState.Connect(ConnectStep.Server())
            return
        }
        val ack = try {
            settings.isHttpAcknowledged(ServerUrl.parse(url).origin)
        } catch (_: Exception) {
            false
        }
        _state.value = SessionState.Connect(ConnectStep.Server(url = url, busy = true))
        probe(url, ack)
    }

    private suspend fun restoreCredentials(): Boolean {
        val url = settings.getServerUrl()
        if (url.isEmpty()) return false
        val parsed = ServerUrl.parse(url)
        rebuild(parsed)
        val mgr = credentials ?: return false
        if (!mgr.restore()) return false
        api?.setTokenProvider(mgr)
        refreshAccess()
        return true
    }

    private suspend fun refreshAccess() {
        val body = try {
            api?.authorize()
        } catch (_: Exception) {
            null
        } ?: return
        userAccess = UserAccessParser.parse(body)
    }

    private suspend fun probeInternal(urlText: String, httpAck: Boolean) {
        apply(ConnectEvent.ProbeStarted(urlText))
        val candidates = try {
            ViewLogic.probeCandidates(urlText)
        } catch (e: ApiError) {
            apply(
                ConnectEvent.ProbeFailed(
                    url = urlText,
                    message = ViewLogic.userMessage(ViewLogic.classifyApiError(e, apiKeyPath = false)),
                ),
            )
            return
        } catch (_: Exception) {
            apply(
                ConnectEvent.ProbeFailed(
                    url = urlText,
                    message = ViewLogic.userMessage(ConnectFailure.UNREACHABLE),
                ),
            )
            return
        }

        var tlsPin: PinPrompt? = null
        for ((index, candidate) in candidates.withIndex()) {
            val last = index == candidates.lastIndex
            if (candidate.isHttp) {
                val originAck = httpAck || settings.isHttpAcknowledged(candidate.origin)
                if (!originAck) {
                    apply(ConnectEvent.HttpNeedsAck(urlText))
                    return
                }
                settings.setHttpAcknowledged(candidate.origin, true)
            }
            try {
                rebuild(candidate)
                val status = api!!.probeStatus()
                if (ViewLogic.versionDecision(status.version) == ConnectDecision.TOO_OLD) {
                    apply(
                        ConnectEvent.ProbeFailed(
                            url = urlText,
                            message = ViewLogic.userMessage(ConnectFailure.TOO_OLD),
                        ),
                    )
                    return
                }
                val offer = ViewLogic.authOffer(status)
                val remembered = settings.getLastAuthMode(candidate.origin)
                apply(
                    ConnectEvent.ProbeOk(
                        server = candidate,
                        version = status.version,
                        offer = offer,
                        mode = ViewLogic.defaultAuthMode(offer, remembered),
                        username = settings.getLastUsername(candidate.origin),
                        reason = connectReason,
                        typedUrl = urlText,
                    ),
                )
                return
            } catch (e: ApiError.Tls) {
                tlsPin = capturePin() ?: tlsPin
                if (!last) continue
                if (tlsPin != null) {
                    apply(ConnectEvent.TlsUntrusted(tlsPin))
                } else {
                    apply(
                        ConnectEvent.ProbeFailed(
                            url = urlText,
                            message = ViewLogic.userMessage(ConnectFailure.TLS_UNTRUSTED),
                        ),
                    )
                }
                return
            } catch (e: ApiError) {
                val failure = ViewLogic.classifyApiError(e, apiKeyPath = false)
                if (ViewLogic.probeAdvances(failure) && !last) continue
                apply(
                    ConnectEvent.ProbeFailed(
                        url = urlText,
                        message = ViewLogic.userMessage(failure),
                    ),
                )
                return
            } catch (_: Exception) {
                if (!last) continue
                if (tlsPin != null) {
                    apply(ConnectEvent.TlsUntrusted(tlsPin))
                } else {
                    apply(
                        ConnectEvent.ProbeFailed(
                            url = urlText,
                            message = ViewLogic.userMessage(ConnectFailure.UNREACHABLE),
                        ),
                    )
                }
                return
            }
        }
    }

    private fun capturePin(): PinPrompt? {
        val parsed = server ?: return null
        val pem = transport.lastPeerPem
        if (pem.isEmpty()) return null
        if (trust.pemFor(parsed.origin) != null) return null
        return PinPrompt(
            origin = parsed.origin,
            fingerprint = sha256Hex(pem),
            pem = pem,
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
        connectReason = ConnectReason.NONE
        val controller = BrowseController(
            api = client,
            settings = settings,
            queue = queue,
            playback = playback,
            scope = scope,
            downloads = downloads,
            onSessionExpired = { expireSession() },
        )
        browse = controller
        controller.start()
        _state.value = SessionState.SignedIn(
            origin = server?.origin.orEmpty(),
            apiKey = mgr.mode == CredentialMode.API_KEY,
            toast = toast,
            browse = controller,
            canDownload = userAccess.canDownload,
        )
        playback.notifyLibraryChanged()
        onSignedIn?.invoke()
    }

    private fun apply(event: ConnectEvent) {
        val current = _state.value
        val step = when (current) {
            is SessionState.Connect -> current.step
            else -> ConnectStep.Server()
        }
        _state.value = SessionState.Connect(ConnectFlow.reduce(step, event))
    }

    private fun currentStep(): ConnectStep? = (_state.value as? SessionState.Connect)?.step

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
    data class Connect(val step: ConnectStep) : SessionState()
    data class SignedIn(
        val origin: String,
        val apiKey: Boolean,
        val toast: String? = null,
        val browse: BrowseController,
        val canDownload: Boolean = true,
    ) : SessionState()
}

internal fun sha256Hex(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { b -> "%02x".format(b) }
}
