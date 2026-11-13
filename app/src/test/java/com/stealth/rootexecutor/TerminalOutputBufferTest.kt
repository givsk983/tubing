package com.stealth.rootexecutor

import org.junit.Assert.*
import org.junit.Test

class TerminalOutputBufferTest {
    @Test fun preservesSplitControlsAndUnicodeWithoutOverflow() {
        val buffer = TerminalOutputBuffer(80, 100)
        listOf("hello\u001b[", "31m红色", "\uD83D", "\uDE00\u001b[0m").forEach(buffer::append)
        assertEquals("hello\u001b[31m红色😀\u001b[0m", buffer.take())
        assertEquals("", buffer.take())
    }

    @Test fun truncationNeverStartsInCsiOrSurrogatePair() {
        val buffer = TerminalOutputBuffer(8, 12)
        buffer.append("abcdef\u001b[38;2;255;0;0m😀tail")
        assertEquals(TerminalOutputBuffer.OMITTED + "😀tail", buffer.snapshot())
        assertEquals(buffer.snapshot(), buffer.take())
    }

    @Test fun oversizedOscAndDcsAreDiscardedThroughTheirActualTerminator() {
        for ((open, close) in listOf("\u001b]0;" to "\u0007", "\u001bP" to "\u001b\\", "\u009d" to "\u009c")) {
            val buffer = TerminalOutputBuffer(8, 12)
            buffer.append(open + "x".repeat(100_000))
            assertEquals(TerminalOutputBuffer.OMITTED, buffer.take())
            buffer.append("still hidden" + close.take(1))
            if (close.length > 1) { assertEquals("", buffer.take()); buffer.append(close.drop(1)) }
            buffer.append("visible")
            assertEquals("visible", buffer.take())
        }
    }

    @Test fun drainKeepsParserStateWhenAControlStraddlesBatches() {
        val buffer = TerminalOutputBuffer(8, 12)
        buffer.append("\u001b]0;")
        assertEquals("\u001b]0;", buffer.take())
        buffer.append("x".repeat(50) + "\u0007tail")
        assertEquals(TerminalOutputBuffer.OMITTED + "tail", buffer.take())
    }

    @Test fun clearResetsAnUnterminatedControlAndTruncationNotice() {
        val buffer = TerminalOutputBuffer(8, 12)
        buffer.append("\u001b]" + "x".repeat(50)); buffer.clear(); buffer.append("new session")
        assertEquals("new session", buffer.snapshot())
    }

    @Test fun liveClearDiscardsTheRemainderOfAnAlreadyOpenControlString() {
        val buffer = TerminalOutputBuffer(80, 100)
        buffer.append("\u001b]0;old title")
        buffer.clear(continueStream = true)
        buffer.append("remainder\u0007visible")
        assertEquals("visible", buffer.take())
    }

    @Test fun chunksDoNotSplitSurrogatesAndRepeatedOverflowRemainsBounded() {
        val buffer = TerminalOutputBuffer(100, 140)
        repeat(1000) { buffer.append("\u001b[32m中文😀\u001b[0m") }
        assertTrue(buffer.snapshot().length <= 140 + TerminalOutputBuffer.OMITTED.length)
        val large = TerminalOutputBuffer()
        large.append("a".repeat(32_767) + "😀tail")
        val first = large.take(32_768)
        assertEquals(32_767, first.length)
        assertEquals("😀tail", large.take())
    }

    @Test fun utf16PairSplitAcrossProducerCallbacksWaitsForItsSecondHalf() {
        val buffer = TerminalOutputBuffer()
        buffer.append("prompt\uD83D")
        assertEquals("prompt", buffer.take())
        assertEquals("", buffer.take())
        buffer.append("\uDE00tail")
        assertEquals("😀tail", buffer.take())
    }

    @Test fun pumpWaitsForConsumptionAndIgnoresOldAcknowledgements() {
        val sent = mutableListOf<Pair<Long, String>>()
        val pump = TerminalOutputPump { id, text -> sent.add(id to text) }
        pump.offer("first"); pump.offer("second")
        assertEquals(1, sent.size)
        pump.consumed(-1); assertEquals(1, sent.size)
        pump.consumed(sent.first().first); assertEquals("second", sent.last().second)
        val stale = sent.last().first
        pump.reset(); pump.offer("new"); pump.offer("later")
        pump.consumed(stale); assertEquals("new", sent.last().second)
        pump.consumed(sent.last().first); assertEquals("later", sent.last().second)
    }

    @Test fun slowWebViewHasOneWriteInFlightAndEventuallyReceivesNewestOutput() {
        val sent = mutableListOf<Pair<Long, String>>()
        val pump = TerminalOutputPump { id, text -> sent.add(id to text) }
        pump.offer("start")
        repeat(4000) { pump.offer("\u001b[31m" + "x".repeat(1024) + "\u001b[0m\r\n") }
        pump.offer("FINAL_MARKER")
        assertEquals(1, sent.size)
        var index = 0
        while (index < sent.size) { pump.consumed(sent[index++].first); assertTrue(sent.size < 30) }
        val text = sent.joinToString("") { it.second }
        assertTrue(text.contains(TerminalOutputBuffer.OMITTED))
        assertTrue(text.endsWith("FINAL_MARKER"))
        assertTrue(text.length < 550_000)
    }
}
