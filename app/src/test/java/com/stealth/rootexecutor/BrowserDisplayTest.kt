package com.stealth.rootexecutor

import org.junit.Assert.*
import org.junit.Test

class BrowserDisplayTest {
    private val files = listOf(
        BrowserEntry("/b.sh", "b.sh", "f", 10, 300),
        BrowserEntry("/Z文件夹", "Z文件夹", "d", 0, 200),
        BrowserEntry("/a.SH", "a.SH", "f", 20, 100),
        BrowserEntry("/A文件夹", "A文件夹", "d", 0, 400)
    )

    @Test fun foldersStayFirstForEveryOrdering() {
        BrowserSort.entries.forEach { sort ->
            assertEquals(listOf("d", "d", "f", "f"), BrowserDisplay.entries(files, "", sort).map { it.type })
        }
    }

    @Test fun nameTimeAndSizeSupportBothDirections() {
        fun names(sort: BrowserSort) = BrowserDisplay.entries(files, "", sort).filter { it.type == "f" }.map { it.name }
        listOf(BrowserSort.NAME_ASC, BrowserSort.OLDEST, BrowserSort.LARGEST).forEach {
            assertEquals(listOf("a.SH", "b.sh"), names(it))
        }
        listOf(BrowserSort.NAME_DESC, BrowserSort.NEWEST, BrowserSort.SMALLEST).forEach {
            assertEquals(listOf("b.sh", "a.SH"), names(it))
        }
    }

    @Test fun searchFiltersCurrentEntriesByLiteralNameIgnoringCase() {
        assertEquals(listOf("a.SH", "b.sh"), BrowserDisplay.entries(files, " .sH ", BrowserSort.NAME_ASC).map { it.name })
        assertEquals(2, BrowserDisplay.entries(files, "文件夹", BrowserSort.NAME_ASC).size)
        assertTrue(BrowserDisplay.entries(files, "[", BrowserSort.NAME_ASC).isEmpty())
        assertEquals(4, files.size)
    }

    @Test fun invalidSavedSortFallsBackToNameAndSwipeUsesDisplayedOrder() {
        assertEquals(BrowserSort.NAME_ASC, BrowserSort.fromKey("unknown"))
        val order = BrowserDisplay.entries(files, ".sh", BrowserSort.NAME_DESC).map { it.path }
        val selection = BrowserSelection()
        selection.swipe(order.first(), true, order)
        selection.swipe(order.last(), true, order)
        assertEquals(order.toSet(), selection.selectedPaths)
    }
}
