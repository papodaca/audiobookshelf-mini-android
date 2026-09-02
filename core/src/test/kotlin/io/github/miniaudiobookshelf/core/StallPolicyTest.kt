package io.github.miniaudiobookshelf.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StallPolicyTest {
    @Test
    fun advancingContinues() {
        val stall = StallPolicy()
        assertEquals(StallDecision.CONTINUE, stall.sample(10.0, true, 0.0, false))
        assertEquals(StallDecision.CONTINUE, stall.sample(12.0, true, 1.0, false))
        assertEquals(StallDecision.CONTINUE, stall.sample(20.0, true, 8.0, false))
    }

    @Test
    fun staticBelowThresholdContinues() {
        val stall = StallPolicy()
        stall.sample(10.0, true, 0.0, false)
        assertEquals(StallDecision.CONTINUE, stall.sample(10.0, true, 4.9, false))
    }

    @Test
    fun staticBeyondThresholdRetries() {
        val stall = StallPolicy()
        stall.sample(10.0, true, 0.0, false)
        assertEquals(StallDecision.RETRY, stall.sample(10.0, true, 5.1, false))
        assertEquals(1, stall.consecutiveRetries)
    }

    @Test
    fun pausedNeverStalls() {
        val stall = StallPolicy()
        stall.sample(10.0, true, 0.0, false)
        assertEquals(StallDecision.CONTINUE, stall.sample(10.0, false, 20.0, false))
        assertEquals(StallDecision.CONTINUE, stall.sample(10.0, false, 40.0, false))
    }

    @Test
    fun twoRetriesThenFail() {
        val stall = StallPolicy()
        stall.sample(10.0, true, 0.0, false)
        assertEquals(StallDecision.RETRY, stall.sample(10.0, true, 5.1, false))
        assertEquals(StallDecision.RETRY, stall.sample(10.0, true, 10.2, false))
        assertEquals(2, stall.consecutiveRetries)
        assertEquals(StallDecision.FAIL, stall.sample(10.0, true, 15.3, false))
    }

    @Test
    fun advancementAfterRetryResets() {
        val stall = StallPolicy()
        stall.sample(10.0, true, 0.0, false)
        stall.sample(10.0, true, 5.1, false)
        assertEquals(1, stall.consecutiveRetries)
        assertEquals(StallDecision.CONTINUE, stall.sample(11.0, true, 6.0, false))
        assertEquals(0, stall.consecutiveRetries)
        assertEquals(StallDecision.RETRY, stall.sample(11.0, true, 11.1, false))
        assertEquals(1, stall.consecutiveRetries)
    }

    @Test
    fun suppressedSampleDoesNotCount() {
        val stall = StallPolicy()
        stall.sample(10.0, true, 0.0, false)
        assertEquals(StallDecision.CONTINUE, stall.sample(0.0, true, 6.0, true))
        assertEquals(0, stall.consecutiveRetries)
        assertEquals(StallDecision.CONTINUE, stall.sample(10.0, true, 7.0, false))
    }
}
