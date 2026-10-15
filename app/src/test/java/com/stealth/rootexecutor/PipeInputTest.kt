package com.stealth.rootexecutor

import org.junit.Assert.*
import org.junit.Test

class PipeInputTest {
    private val sent = mutableListOf<String>()
    private val echo = StringBuilder()
    private var interrupts = 0
    private var closed = 0
    private val input = PipeInput({ sent.add(it) }, { echo.append(it) }, { interrupts++ }, { closed++ })

    @Test fun editsChineseAndSurrogatePairsBeforeSubmission() {
        input.accept("你好😀\u007f\r")
        assertEquals(listOf("你好\n"), sent)
    }
    @Test fun sendsOnlyCompleteLinesAndNormalizesPasteCrLfAcrossChunks() {
        input.accept("first")
        assertTrue(sent.isEmpty())
        input.accept("\r")
        input.accept("\nsecond\nthird\r\n")
        assertEquals(listOf("first\n", "second\n", "third\n"), sent)
    }
    @Test fun interruptClearsUnsubmittedTextWithoutClosingInput() {
        input.accept("discard\u0003next\r")
        assertEquals(1, interrupts)
        assertEquals(0, closed)
        assertEquals(listOf("next\n"), sent)
    }
    @Test fun eofFlushesPendingTextThenClosesOnNextEof() {
        input.accept("end\u0004")
        assertEquals(listOf("end"), sent)
        assertEquals(0, closed)
        input.accept("\u0004ignored\r")
        assertEquals(1, closed)
        assertEquals(listOf("end"), sent)
    }
    @Test fun directionKeysAreFilteredAndEscapeDoesNotSwallowNextCharacter() {
        input.accept("\u001b[")
        input.accept("Aok\u001b")
        input.accept("z\r")
        assertEquals(listOf("okz\n"), sent)
    }
    @Test fun clearLineDiscardsPendingInput() {
        input.accept("old\u0015new\r")
        assertEquals(listOf("new\n"), sent)
    }
}
