package com.stealth.rootexecutor

import org.junit.Assert.*
import org.junit.Test

class QuickExitRequestTest {
    private fun QuickExitRequest.ready(id: String = "first", enabled: Boolean = true,
        running: Boolean = false, preparing: Boolean = false, active: Boolean = false) =
        canExit(id, enabled, running, preparing, active)

    @Test fun onlyEnabledShortcutCompletionRequestsExit() {
        val request = QuickExitRequest()
        assertFalse(request.ready())
        request.complete("first", shortcut = false, enabled = true)
        assertFalse(request.ready())
        request.complete("first", shortcut = true, enabled = false)
        assertFalse(request.ready())
        request.complete("first", shortcut = true, enabled = true)
        assertTrue(request.ready())
    }

    @Test fun waitsUntilExecutionAndServiceCleanupHaveFinished() {
        val request = QuickExitRequest().apply { complete("first", true, true) }
        assertFalse(request.ready(running = true))
        assertFalse(request.ready(active = true))
        assertFalse(request.ready(preparing = true))
        assertTrue(request.ready())
    }

    @Test fun newLaunchInvalidatesExitEvenIfItLaterFails() {
        val request = QuickExitRequest().apply { complete("first", true, true) }
        request.cancel()
        assertFalse(request.ready())
        assertFalse(request.ready(id = "second"))
    }

    @Test fun staleCallbackCannotExitANewerSession() {
        val request = QuickExitRequest().apply { complete("first", true, true) }
        assertFalse(request.ready(id = "second"))
        assertFalse(request.matches(null))
        request.complete("second", true, true)
        assertFalse(request.ready())
        assertTrue(request.ready(id = "second"))
    }

    @Test fun disablingSettingWhileCleanupIsPendingPreventsExit() {
        val request = QuickExitRequest().apply { complete("first", true, true) }
        assertFalse(request.ready(enabled = false))
        request.cancel()
        assertFalse(request.ready())
    }
}
