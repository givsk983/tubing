package com.stealth.rootexecutor

import org.junit.Assert.*
import org.junit.Test

class InputSendModeTest {
    @Test fun legacyAndUnknownModeDefaultToDelay() {
        assertEquals(InputSendMode.DELAY, InputSendMode.fromKey(null))
        assertEquals(InputSendMode.DELAY, InputSendMode.fromKey(""))
        assertEquals(InputSendMode.DELAY, InputSendMode.fromKey("legacy"))
        assertEquals(InputSendMode.PROMPT, InputSendMode.fromKey("prompt"))
    }
    @Test fun delayWorksWithoutPromptsAndIgnoresOldPromptMetadata() {
        assertTrue(InputSendMode.DELAY.valid(listOf("1", "abc"), emptyList()))
        assertTrue(InputSendMode.DELAY.valid(listOf("1", "abc"), listOf("old prompt")))
        assertEquals(1_500L, InputSendMode.INITIAL_DELAY_MS)
        assertEquals(900L, InputSendMode.BETWEEN_INPUTS_MS)
    }
    @Test fun promptModeRequiresOneUsablePromptPerStep() {
        assertTrue(InputSendMode.PROMPT.valid(listOf("1", "abc"), listOf("菜单>", "参数>")))
        assertFalse(InputSendMode.PROMPT.valid(listOf("1"), emptyList()))
        assertFalse(InputSendMode.PROMPT.valid(listOf("1"), listOf(" ")))
        assertFalse(InputSendMode.PROMPT.valid(listOf("1"), listOf("x".repeat(513))))
    }
    @Test fun bothModesKeepInputLimits() {
        InputSendMode.entries.forEach { mode ->
            assertFalse(mode.valid(List(51) { "1" }, List(51) { ">" }))
            assertFalse(mode.valid(listOf("x".repeat(4097)), listOf(">")))
            assertTrue(mode.valid(emptyList(), emptyList()))
        }
    }
}
