package com.stealth.rootexecutor

import org.junit.Assert.*
import org.junit.Test

class ExecutionStartGateTest {
    @Test fun preparationBlocksRepeatedClicksAndOtherLaunchEntryPoints() {
        val gate = ExecutionStartGate()
        assertTrue(gate.tryPrepare(100L))
        assertFalse(gate.tryPrepare(101L))
        assertFalse(gate.tryStart(102L))
        // Root lookup can take longer than three seconds without unlocking another request.
        assertFalse(gate.tryPrepare(10_000L))
        gate.finishPreparation()
        assertTrue(gate.tryStart(10_001L))
    }

    @Test fun cooldownStartsAtActualLaunchAndLastsThreeSeconds() {
        val gate = ExecutionStartGate()
        gate.tryPrepare(100L)
        gate.finishPreparation()
        assertTrue(gate.tryStart(2_000L))
        assertEquals(3_000L, gate.remainingMillis(2_000L))
        assertFalse(gate.tryStart(4_999L))
        assertFalse(gate.tryPrepare(4_999L))
        assertEquals(1L, gate.remainingMillis(4_999L))
        assertTrue(gate.tryStart(5_000L))
    }

    @Test fun failedLookupReleasesPreparationWithoutUnnecessaryCooldown() {
        val gate = ExecutionStartGate()
        gate.tryPrepare(100L)
        gate.finishPreparation()
        assertFalse(gate.isPreparing())
        assertTrue(gate.tryPrepare(101L))
    }

    @Test fun serviceStartFailureAllowsRetry() {
        val gate = ExecutionStartGate()
        assertTrue(gate.tryStart(100L))
        gate.resetAfterFailure()
        assertEquals(0L, gate.remainingMillis(101L))
        assertTrue(gate.tryStart(101L))
    }

    @Test fun repeatedRequestsDuringCooldownDoNotExtendIt() {
        val gate = ExecutionStartGate()
        assertTrue(gate.tryStart(100L))
        repeat(100) { assertFalse(gate.tryStart(101L + it)) }
        assertEquals(0L, gate.remainingMillis(3_100L))
        assertTrue(gate.tryPrepare(3_100L))
    }
}
