package com.stealth.rootexecutor

import org.junit.Assert.*
import org.junit.Test
import java.io.Writer

class BatchedSessionLogTest {
    private class RecordingWriter : Writer() {
        val text = StringBuilder()
        var flushes = 0
        var closed = false
        override fun write(chars: CharArray, offset: Int, length: Int) { text.append(chars, offset, length) }
        override fun flush() { flushes++ }
        override fun close() { closed = true }
    }

    @Test fun manySmallOutputsFlushBySizeInsteadOfEveryCallback() {
        val writer = RecordingWriter()
        val log = BatchedSessionLog(writer, { 0 }, batchChars = 100)
        repeat(1000) { log.append("abc") }
        assertEquals(29, writer.flushes) // Each flush contains 34 three-character callbacks.
        assertEquals("abc".repeat(1000), writer.text.toString())
    }

    @Test fun timeThresholdFlushesTheNextOutputAndCloseKeepsTheTail() {
        var time = 0L
        val writer = RecordingWriter()
        val log = BatchedSessionLog(writer, { time })
        log.append("start"); assertEquals(0, writer.flushes)
        time = 1000; log.append("next"); assertEquals(1, writer.flushes)
        log.append("tail"); log.close()
        assertTrue(writer.closed)
        assertEquals("startnexttail", writer.text.toString())
    }

    @Test fun logCapIsExactAndIndependentOfDisplayTruncationNotices() {
        val writer = RecordingWriter()
        val log = BatchedSessionLog(writer, { 0 }, limit = 10)
        log.append("12345678"); log.append("901234"); log.append("ignored"); log.close()
        assertEquals("1234567890", writer.text.toString())
    }
}
