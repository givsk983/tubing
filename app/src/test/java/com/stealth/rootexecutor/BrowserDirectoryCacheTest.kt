package com.stealth.rootexecutor

import org.junit.Assert.*
import org.junit.Test

class BrowserDirectoryCacheTest {
    private fun entries(count: Int) = (0 until count).map { BrowserEntry("/entry-$it", "entry-$it", "f", 0, 0) }

    @Test fun emptySnapshotsAndMissingDirectoriesAreDistinct() {
        val cache = BrowserDirectoryCache()
        assertNull(cache["/empty"])
        cache.put("/empty/", emptyList())
        assertNotNull(cache["/empty"])
        assertTrue(cache["/empty"]!!.isEmpty())
    }

    @Test fun invalidManualPathsCannotAliasTheRootSnapshot() {
        val cache = BrowserDirectoryCache()
        cache.put("/", entries(1))
        assertNull(cache[""])
        assertNull(cache["relative"])
        cache.remove("")
        assertNotNull(cache["/"])
    }

    @Test fun snapshotsCannotChangeBehindTheDisplayedList() {
        val cache = BrowserDirectoryCache()
        val source = entries(2).toMutableList()
        cache.put("/folder", source)
        source.clear()
        assertEquals(2, cache["/folder"]!!.size)
    }

    @Test fun recentlyReopenedDirectoriesSurviveEviction() {
        val cache = BrowserDirectoryCache(maxDirectories = 2)
        cache.put("/a", entries(1)); cache.put("/b", entries(1))
        assertNotNull(cache["/a"])
        cache.put("/c", entries(1))
        assertNull(cache["/b"])
        assertNotNull(cache["/a"])
    }

    @Test fun entryBudgetBoundsMemoryAndMutationsCanInvalidateSnapshots() {
        val cache = BrowserDirectoryCache(maxEntries = 3)
        cache.put("/a", entries(2)); cache.put("/b", entries(2))
        assertNull(cache["/a"])
        cache.put("/huge", entries(4))
        assertNull(cache["/huge"])
        cache.remove("/b/")
        assertNull(cache["/b"])
        cache.put("/a", entries(1)); cache.clear()
        assertNull(cache["/a"])
    }

    @Test fun lateResultsCannotReplaceNewNavigationAndPanesAreIndependent() {
        val tokens = BrowserRequestTokens()
        val firstLeft = tokens.begin(true)
        val right = tokens.begin(false)
        val nextLeft = tokens.begin(true)
        assertFalse(tokens.isCurrent(true, firstLeft))
        assertTrue(tokens.isCurrent(true, nextLeft))
        assertTrue(tokens.isCurrent(false, right))
        tokens.invalidateAll()
        assertFalse(tokens.isCurrent(true, nextLeft))
        assertFalse(tokens.isCurrent(false, right))
    }
}
