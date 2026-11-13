package com.stealth.rootexecutor

import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Each pane has at most one useful read; cancellation also interrupts its root query. */
internal class BrowserDirectoryLoader(private val dispatch: (() -> Unit) -> Unit) : AutoCloseable {
    private val executor = Executors.newFixedThreadPool(2)
    private val requests = BrowserRequestTokens()
    private val tasks = arrayOfNulls<Future<*>>(2)

    fun load(left: Boolean, read: () -> Pair<CommandResult, List<BrowserEntry>>,
             done: (CommandResult, List<BrowserEntry>) -> Unit) {
        val token = requests.begin(left)
        val index = if (left) 0 else 1
        tasks[index]?.cancel(true)
        tasks[index] = executor.submit {
            if (!requests.isCurrent(left, token)) return@submit
            val result = try { read() }
            catch (_: InterruptedException) { Thread.currentThread().interrupt(); return@submit }
            catch (error: Exception) { CommandResult(-1, error.message ?: "读取失败") to emptyList() }
            dispatch { if (requests.isCurrent(left, token)) done(result.first, result.second) }
        }
    }

    override fun close() {
        requests.invalidateAll()
        tasks.forEach { it?.cancel(true) }
        executor.shutdownNow()
    }
}
