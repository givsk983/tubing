package com.stealth.rootexecutor

import java.io.Closeable
import java.io.Writer

/** Keep the existing log cap while amortizing writes; close always commits the final batch. */
internal class BatchedSessionLog(
    private val writer: Writer,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val limit: Int = 131_072,
    private val batchChars: Int = 8192,
    private val flushMillis: Long = 1000
) : Closeable {
    private var written = 0
    private var unflushed = 0
    private var lastFlush = clock()

    @Synchronized fun append(value: String) {
        val count = minOf(value.length, limit - written)
        if (count <= 0) return
        writer.write(value, 0, count)
        written += count; unflushed += count
        if (unflushed >= batchChars || clock() - lastFlush >= flushMillis || written == limit) {
            writer.flush(); unflushed = 0; lastFlush = clock()
        }
    }

    @Synchronized override fun close() { writer.close() }
}
