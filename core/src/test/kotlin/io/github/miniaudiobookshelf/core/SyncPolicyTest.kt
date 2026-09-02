package io.github.miniaudiobookshelf.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SyncPolicyTest {
    @Test
    fun firstSyncAtTwenty() {
        val policy = policyAt(0.0, 0)
        assertEquals(SyncDecision.NOOP, policy.onTick(10.0, true, 10))
        assertEquals(SyncDecision.SYNC, policy.onTick(20.0, true, 20))
    }

    @Test
    fun subsequentSyncEveryTen() {
        val policy = policyAt(0.0, 0)
        assertEquals(SyncDecision.SYNC, policy.onTick(20.0, true, 20))
        policy.commitSync(20.0, 20)
        assertEquals(SyncDecision.NOOP, policy.onTick(25.0, true, 25))
        assertEquals(SyncDecision.SYNC, policy.onTick(30.0, true, 30))
    }

    @Test
    fun accumulatorPauses() {
        val policy = policyAt(0.0, 0)
        policy.onTick(10.0, true, 10)
        policy.onPause(10.0, 10)
        assertEquals(SyncDecision.NOOP, policy.onTick(10.0, false, 40))
        policy.onTick(10.0, true, 40)
        assertEquals(SyncDecision.SYNC, policy.onTick(20.0, true, 50))
    }

    @Test
    fun deltasDoNotOverlap() {
        val policy = policyAt(0.0, 0)
        policy.onTick(20.0, true, 20)
        assertEquals(20.0, policy.commitSync(20.0, 20), 0.0001)
        policy.onTick(30.0, true, 30)
        assertEquals(10.0, policy.commitSync(30.0, 30), 0.0001)
    }

    @Test
    fun smallPositionMoveIsNoop() {
        val policy = policyAt(0.0, 0)
        assertEquals(SyncDecision.NOOP, policy.onTick(0.4, true, 20))
    }

    @Test
    fun pauseSeekStopFlush() {
        assertEquals(SyncDecision.FORCE_FLUSH, policyAt(0.0, 0).onPause(5.0, 5))
        assertEquals(SyncDecision.FORCE_FLUSH, policyAt(0.0, 0).onSeek(40.0, 2))
        assertEquals(SyncDecision.FORCE_FLUSH, policyAt(0.0, 0).onStop(80.0, 3))
    }

    @Test
    fun seekBurstCoalesces() {
        val policy = policyAt(0.0, 0)
        assertEquals(SyncDecision.FORCE_FLUSH, policy.onSeek(10.0, 1))
        policy.commitSync(10.0, 1)
        assertEquals(SyncDecision.NOOP, policy.onSeek(20.0, 1))
        assertEquals(SyncDecision.FORCE_FLUSH, policy.onSeek(30.0, 2))
    }

    @Test
    fun notFoundReopensOnce() {
        val policy = policyAt(0.0, 0)
        assertEquals(SyncDecision.REOPEN_SESSION, policy.onSyncOutcome(SyncOutcome.NOT_FOUND))
        assertEquals(SyncDecision.MARK_UNHEALTHY, policy.onSyncOutcome(SyncOutcome.NOT_FOUND))
    }

    @Test
    fun reopenAheadConflicts() {
        val policy = policyAt(100.0, 0)
        assertEquals(SyncDecision.RAISE_CONFLICT, policy.onReopened(100.0, 161.0))
    }

    @Test
    fun reopenBehindIsSilent() {
        val policy = policyAt(100.0, 0)
        assertEquals(SyncDecision.CONTINUE_LOCAL, policy.onReopened(100.0, 20.0))
    }

    @Test
    fun reopenWithinSixtyIsSilent() {
        val policy = policyAt(100.0, 0)
        assertEquals(SyncDecision.CONTINUE_LOCAL, policy.onReopened(100.0, 150.0))
        assertEquals(SyncDecision.CONTINUE_LOCAL, policy.onReopened(100.0, 50.0))
    }

    @Test
    fun repeatedFailureIsUnhealthy() {
        val policy = policyAt(0.0, 0)
        assertEquals(SyncDecision.NOOP, policy.onSyncOutcome(SyncOutcome.FAILED))
        assertEquals(SyncDecision.MARK_UNHEALTHY, policy.onSyncOutcome(SyncOutcome.FAILED))
        assertEquals(SyncDecision.NOOP, policy.onSyncOutcome(SyncOutcome.FAILED))
    }

    @Test
    fun recoveryFlushesHeldPosition() {
        val policy = policyAt(0.0, 0)
        policy.onTick(20.0, true, 20)
        policy.commitSync(20.0, 20)
        policy.onSyncOutcome(SyncOutcome.FAILED)
        policy.onSyncOutcome(SyncOutcome.FAILED)
        assertEquals(20.0, policy.lastGoodPosition, 0.0001)
        assertEquals(SyncDecision.MARK_RECOVERED, policy.onSyncOutcome(SyncOutcome.OK))
    }

    @Test
    fun stopWritesChangedPositionUnderTwenty() {
        val policy = policyAt(0.0, 0)
        policy.onTick(5.0, true, 5)
        assertEquals(SyncDecision.FORCE_FLUSH, policy.onStop(400.0, 5))
    }
}

private fun policyAt(position: Double, now: Long): SyncPolicy {
    val policy = SyncPolicy()
    policy.startSession(position, now)
    return policy
}
