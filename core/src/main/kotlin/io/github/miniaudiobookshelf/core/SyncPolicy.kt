package io.github.miniaudiobookshelf.core

enum class SyncDecision {
    NOOP,
    SYNC,
    FORCE_FLUSH,
    REOPEN_SESSION,
    RAISE_CONFLICT,
    MARK_UNHEALTHY,
    MARK_RECOVERED,
    CONTINUE_LOCAL,
}

enum class SyncOutcome {
    NONE,
    OK,
    NOT_FOUND,
    FAILED,
}

class SyncPolicy {
    var lastDelta: Double = 0.0
        private set
    var lastGoodPosition: Double = 0.0
        private set

    private var accumulated = 0L
    private var playingSince = -1L
    private var lastSyncedAccumulated = 0L
    private var lastForceAt = -1L
    private var sessionOpenPosition = 0.0
    private var lastSyncedPosition = 0.0
    private var firstSyncDone = false
    private var reopenIssued = false
    private var unhealthy = false
    private var failCount = 0

    fun startSession(position: Double, now: Long) {
        accumulated = 0
        playingSince = now
        lastSyncedAccumulated = 0
        lastForceAt = -1
        sessionOpenPosition = position
        lastSyncedPosition = position
        lastGoodPosition = position
        lastDelta = 0.0
        firstSyncDone = false
        reopenIssued = false
        unhealthy = false
        failCount = 0
    }

    fun onTick(position: Double, playing: Boolean, now: Long): SyncDecision {
        updateAccumulator(playing, now)
        val played = currentPlayed(now)
        if (kotlin.math.abs(position - lastSyncedPosition) < MIN_POSITION_DELTA) {
            return SyncDecision.NOOP
        }
        if (!firstSyncDone) {
            return if (played >= FIRST_SYNC_SECONDS) SyncDecision.SYNC else SyncDecision.NOOP
        }
        if (played - lastSyncedAccumulated >= INTERVAL_SECONDS) {
            return SyncDecision.SYNC
        }
        return SyncDecision.NOOP
    }

    fun onPause(position: Double, now: Long): SyncDecision {
        updateAccumulator(false, now)
        return maybeForceFlush(now)
    }

    fun onSeek(position: Double, now: Long): SyncDecision = maybeForceFlush(now)

    fun onStop(position: Double, now: Long): SyncDecision {
        updateAccumulator(false, now)
        if (kotlin.math.abs(position - sessionOpenPosition) >= 0.01 || firstSyncDone) {
            return maybeForceFlush(now)
        }
        return SyncDecision.NOOP
    }

    fun onSyncOutcome(outcome: SyncOutcome): SyncDecision {
        if (outcome == SyncOutcome.OK) {
            failCount = 0
            reopenIssued = false
            if (unhealthy) {
                unhealthy = false
                return SyncDecision.MARK_RECOVERED
            }
            return SyncDecision.NOOP
        }
        if (outcome == SyncOutcome.NOT_FOUND) {
            if (!reopenIssued) {
                reopenIssued = true
                return SyncDecision.REOPEN_SESSION
            }
            failCount += 1
            if (!unhealthy) {
                unhealthy = true
                return SyncDecision.MARK_UNHEALTHY
            }
            return SyncDecision.NOOP
        }
        failCount += 1
        if (failCount >= 2 && !unhealthy) {
            unhealthy = true
            return SyncDecision.MARK_UNHEALTHY
        }
        return SyncDecision.NOOP
    }

    fun onReopened(localPosition: Double, serverPosition: Double): SyncDecision {
        if (serverPosition - localPosition > CONFLICT_AHEAD_SECONDS) {
            return SyncDecision.RAISE_CONFLICT
        }
        return SyncDecision.CONTINUE_LOCAL
    }

    fun commitSync(position: Double, now: Long): Double {
        val played = currentPlayed(now)
        lastDelta = (played - lastSyncedAccumulated).toDouble()
        if (lastDelta < 0) lastDelta = 0.0
        lastSyncedAccumulated = played
        lastSyncedPosition = position
        lastGoodPosition = position
        lastForceAt = now
        firstSyncDone = true
        return lastDelta
    }

    private fun maybeForceFlush(now: Long): SyncDecision {
        if (lastForceAt >= 0 && now - lastForceAt < COALESCE_SECONDS) {
            return SyncDecision.NOOP
        }
        return SyncDecision.FORCE_FLUSH
    }

    private fun updateAccumulator(playing: Boolean, now: Long) {
        if (playing) {
            if (playingSince < 0) playingSince = now
        } else if (playingSince >= 0) {
            accumulated += now - playingSince
            playingSince = -1
        }
    }

    private fun currentPlayed(now: Long): Long {
        return if (playingSince < 0) accumulated else accumulated + (now - playingSince)
    }

    companion object {
        const val FIRST_SYNC_SECONDS = 20L
        const val INTERVAL_SECONDS = 10L
        const val COALESCE_SECONDS = 1L
        const val MIN_POSITION_DELTA = 1.0
        const val CONFLICT_AHEAD_SECONDS = 60.0
    }
}
