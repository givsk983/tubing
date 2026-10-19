package com.stealth.rootexecutor

/** Shared by both interfaces so rebuilding the UI cannot bypass startup protection. */
class ExecutionStartGate(private val cooldownMillis: Long = 3_000L) {
    private var preparing = false
    private var nextAllowedAt = 0L

    @Synchronized fun tryPrepare(now: Long): Boolean {
        if (preparing || now < nextAllowedAt) return false
        preparing = true
        return true
    }

    @Synchronized fun finishPreparation() { preparing = false }

    @Synchronized fun tryStart(now: Long): Boolean {
        if (preparing || now < nextAllowedAt) return false
        nextAllowedAt = now + cooldownMillis
        return true
    }

    @Synchronized fun isPreparing() = preparing
    @Synchronized fun remainingMillis(now: Long) = (nextAllowedAt - now).coerceAtLeast(0L)
    @Synchronized fun resetAfterFailure() { preparing = false; nextAllowedAt = 0L }
}
