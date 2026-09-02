package io.github.miniaudiobookshelf.android.player

import io.github.miniaudiobookshelf.core.ApiClient
import io.github.miniaudiobookshelf.core.ApiError
import io.github.miniaudiobookshelf.core.Clock
import io.github.miniaudiobookshelf.core.SyncDecision
import io.github.miniaudiobookshelf.core.SyncOutcome
import io.github.miniaudiobookshelf.core.SyncPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class SyncDriver(
    private val engine: PlaybackEngine,
    private val api: ApiClient,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val onConflict: (local: Double, server: Double) -> Unit,
) {
    private val policy = SyncPolicy()
    private var sending = false
    private var started = false
    private var lastItem = ""
    private var lastIntended = IntendedState.STOPPED
    private var lastSeekGeneration = 0
    private var tickJob: Job? = null

    fun start() {
        if (tickJob != null) return
        tickJob = scope.launch {
            while (isActive) {
                onTick()
                delay(1000)
            }
        }
    }

    fun stop() {
        tickJob?.cancel()
        tickJob = null
    }

    suspend fun flushNow() {
        if (engine.state.value.itemId.isEmpty()) return
        val now = clock.unixSeconds()
        val decision = policy.onStop(engine.state.value.position, now)
        applyDecision(decision)
    }

    private suspend fun onTick() {
        val state = engine.state.value
        val now = clock.unixSeconds()
        if (state.itemId.isEmpty()) {
            started = false
            lastItem = ""
            lastIntended = IntendedState.STOPPED
            return
        }
        if (!started || lastItem != state.itemId) {
            policy.startSession(state.position, now)
            started = true
            lastItem = state.itemId
            lastIntended = state.intended
            lastSeekGeneration = state.seekGeneration
            engine.setSyncUnhealthy(false)
            return
        }

        val decision = when {
            state.seekGeneration != lastSeekGeneration -> {
                lastSeekGeneration = state.seekGeneration
                policy.onSeek(state.position, now)
            }
            state.intended != lastIntended -> {
                val previous = lastIntended
                lastIntended = state.intended
                when (state.intended) {
                    IntendedState.PAUSED -> policy.onPause(state.position, now)
                    IntendedState.STOPPED -> policy.onStop(state.position, now)
                    IntendedState.PLAYING -> if (previous == IntendedState.PAUSED) {
                        policy.onTick(state.position, true, now)
                    } else {
                        policy.onTick(state.position, true, now)
                    }
                }
            }
            else -> policy.onTick(state.position, state.intended == IntendedState.PLAYING, now)
        }
        applyDecision(decision)
    }

    private suspend fun applyDecision(decision: SyncDecision) {
        when (decision) {
            SyncDecision.SYNC, SyncDecision.FORCE_FLUSH -> sendProgress()
            SyncDecision.REOPEN_SESSION -> {
                try {
                    engine.reopenSession()
                } catch (_: Exception) {
                    applyDecision(policy.onSyncOutcome(SyncOutcome.FAILED))
                    return
                }
                val next = policy.onReopened(engine.state.value.position, engine.lastServerCurrentTime)
                applyDecision(next)
            }
            SyncDecision.RAISE_CONFLICT -> {
                engine.pause()
                onConflict(engine.state.value.position, engine.lastServerCurrentTime)
            }
            SyncDecision.MARK_UNHEALTHY -> engine.setSyncUnhealthy(true)
            SyncDecision.MARK_RECOVERED -> {
                engine.setSyncUnhealthy(false)
                sendProgress()
            }
            SyncDecision.CONTINUE_LOCAL -> sendProgress()
            SyncDecision.NOOP -> Unit
        }
    }

    private suspend fun sendProgress() {
        val id = engine.currentSessionId
        if (id.isEmpty() || sending) return
        sending = true
        val state = engine.state.value
        val now = clock.unixSeconds()
        val position = state.position
        val duration = state.duration
        val delta = policy.commitSync(position, now)
        if (!engine.usesLiveSync) {
            engine.noteLocalListen(position, delta, duration, now * 1000)
            sending = false
            applyDecision(policy.onSyncOutcome(SyncOutcome.OK))
            if (state.intended != IntendedState.PLAYING) {
                engine.flushLocal()
            }
            return
        }
        try {
            api.syncSession(id, position, duration, delta)
            sending = false
            applyDecision(policy.onSyncOutcome(SyncOutcome.OK))
        } catch (e: ApiError.NotFound) {
            sending = false
            applyDecision(policy.onSyncOutcome(SyncOutcome.NOT_FOUND))
        } catch (_: Exception) {
            sending = false
            applyDecision(policy.onSyncOutcome(SyncOutcome.FAILED))
        }
    }
}
