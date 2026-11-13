package com.stealth.rootexecutor

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class BrowserDirectoryLoaderTest {
    @Test fun rapidNavigationInterruptsOldReadsAndLeavesOtherPaneAlone() {
        val callbacks = LinkedBlockingQueue<() -> Unit>()
        val loader = BrowserDirectoryLoader { callbacks.add(it) }
        val started = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val rightDone = CountDownLatch(1)
        val results = mutableListOf<String>()
        try {
            loader.load(true, {
                started.countDown()
                try { CountDownLatch(1).await(); error("unreachable") }
                finally { cancelled.countDown() }
            }) { _, _ -> results.add("stale") }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            loader.load(false, { rightDone.countDown(); CommandResult(0, "right") to emptyList() }) { result, _ -> results.add(result.output) }
            loader.load(true, { CommandResult(0, "latest") to emptyList() }) { result, _ -> results.add(result.output) }
            assertTrue(cancelled.await(5, TimeUnit.SECONDS))
            assertTrue(rightDone.await(5, TimeUnit.SECONDS))
            repeat(2) { checkNotNull(callbacks.poll(5, TimeUnit.SECONDS)).invoke() }
            assertEquals(setOf("right", "latest"), results.toSet())
        } finally { loader.close() }
    }

    @Test fun alreadyPostedResultsCannotOutliveNewNavigationOrClose() {
        val callbacks = LinkedBlockingQueue<() -> Unit>()
        val loader = BrowserDirectoryLoader { callbacks.add(it) }
        var delivered = 0
        loader.load(true, { CommandResult(0, "old") to emptyList() }) { _, _ -> delivered++ }
        val old = checkNotNull(callbacks.poll(5, TimeUnit.SECONDS))
        loader.load(true, { CommandResult(0, "new") to emptyList() }) { _, _ -> delivered++ }
        val latest = checkNotNull(callbacks.poll(5, TimeUnit.SECONDS))
        old(); assertEquals(0, delivered)
        loader.close(); latest(); assertEquals(0, delivered)
    }
}
