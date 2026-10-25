package com.stealth.rootexecutor

import java.util.concurrent.atomic.AtomicLongArray

/** Small display snapshots; every visit still validates the directory in the background. */
internal class BrowserDirectoryCache(private val maxDirectories: Int = 24, private val maxEntries: Int = 12_000) {
    private val snapshots = LinkedHashMap<String, List<BrowserEntry>>(24, 0.75f, true)
    private fun key(path: String) = path.trimEnd('/').ifEmpty { "/" }
    operator fun get(path: String): List<BrowserEntry>? = if (path.startsWith('/')) snapshots[key(path)] else null
    fun put(path: String, entries: List<BrowserEntry>) {
        if (!path.startsWith('/')) return
        snapshots.remove(key(path))
        if (entries.size > maxEntries) return
        snapshots[key(path)] = entries.toList()
        while (snapshots.size > maxDirectories || snapshots.values.sumOf { it.size } > maxEntries) {
            snapshots.remove(snapshots.keys.first())
        }
    }
    fun remove(path: String) { if (path.startsWith('/')) snapshots.remove(key(path)) }
    fun clear() { snapshots.clear() }
}

/** A late directory read must not overwrite a newer navigation or the other pane. */
internal class BrowserRequestTokens {
    private val versions = AtomicLongArray(2)
    fun begin(left: Boolean): Long = versions.incrementAndGet(if (left) 0 else 1)
    fun isCurrent(left: Boolean, token: Long) = versions.get(if (left) 0 else 1) == token
    fun invalidateAll() { versions.incrementAndGet(0); versions.incrementAndGet(1) }
}
