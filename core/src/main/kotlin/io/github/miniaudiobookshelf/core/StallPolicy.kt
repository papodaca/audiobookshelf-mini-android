package io.github.miniaudiobookshelf.core

enum class StallDecision {
    CONTINUE,
    RETRY,
    FAIL,
}

class StallPolicy {
    var consecutiveRetries: Int = 0
        private set

    private var haveSample = false
    private var lastPosition = 0.0
    private var lastAdvanceAt = 0.0

    fun reset() {
        haveSample = false
        lastPosition = 0.0
        lastAdvanceAt = 0.0
        consecutiveRetries = 0
    }

    fun sample(position: Double, intendedPlaying: Boolean, now: Double, suppressed: Boolean): StallDecision {
        if (suppressed) {
            if (haveSample) {
                lastAdvanceAt = now
            }
            return StallDecision.CONTINUE
        }
        if (!intendedPlaying) {
            haveSample = true
            lastPosition = position
            lastAdvanceAt = now
            return StallDecision.CONTINUE
        }
        if (!haveSample) {
            haveSample = true
            lastPosition = position
            lastAdvanceAt = now
            return StallDecision.CONTINUE
        }
        if (position > lastPosition + 0.0001) {
            lastPosition = position
            lastAdvanceAt = now
            consecutiveRetries = 0
            return StallDecision.CONTINUE
        }
        if (now - lastAdvanceAt < THRESHOLD_SECONDS) {
            return StallDecision.CONTINUE
        }
        lastAdvanceAt = now
        lastPosition = position
        return if (consecutiveRetries < MAX_RETRIES) {
            consecutiveRetries += 1
            StallDecision.RETRY
        } else {
            StallDecision.FAIL
        }
    }

    companion object {
        const val THRESHOLD_SECONDS = 5.0
        const val MAX_RETRIES = 2
    }
}
