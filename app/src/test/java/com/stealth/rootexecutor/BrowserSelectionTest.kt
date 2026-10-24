package com.stealth.rootexecutor

import org.junit.Assert.*
import org.junit.Test

class BrowserSelectionTest {
    private val order = listOf("A", "B", "C", "D", "E", "F")

    @Test fun firstSwipeEntersModeInEitherDirection() {
        listOf(false, true).forEach { right ->
            val selection = BrowserSelection()
            selection.swipe("B", right, order)
            assertTrue(selection.inSelectionMode)
            assertEquals(setOf("B"), selection.selectedPaths)
        }
    }

    @Test fun consecutiveRightSwipesIncludeEndpointsAndEveryIntermediateRow() {
        val selection = BrowserSelection()
        selection.swipe("B", true, order)
        selection.swipe("E", true, order)
        assertEquals(setOf("B", "C", "D", "E"), selection.selectedPaths)
        selection.swipe("A", true, order)
        assertEquals(setOf("A", "B", "C", "D", "E"), selection.selectedPaths)
    }

    @Test fun tapsAreAdditiveAndDoNotReplaceTheLastRightSwipeAnchor() {
        val selection = BrowserSelection()
        selection.swipe("B", true, order)
        selection.add("F")
        selection.add("F")
        selection.swipe("D", true, order)
        assertEquals(setOf("B", "C", "D", "F"), selection.selectedPaths)
    }

    @Test fun leftSwipeAddsOnlyItsRowAndDoesNotCreateARangeAnchor() {
        val selection = BrowserSelection()
        selection.swipe("A", false, order)
        selection.swipe("D", false, order)
        selection.swipe("F", true, order)
        assertEquals(setOf("A", "D", "F"), selection.selectedPaths)
    }

    @Test fun clearResetsModeAndAnchorAndPanesAreIndependent() {
        val left = BrowserSelection()
        val right = BrowserSelection()
        left.swipe("A", true, order)
        right.swipe("B", true, order)
        left.clear()
        assertFalse(left.inSelectionMode)
        assertTrue(left.selectedPaths.isEmpty())
        assertEquals(setOf("B"), right.selectedPaths)
        left.swipe("F", true, order)
        assertEquals(setOf("F"), left.selectedPaths)
    }

    @Test fun refreshUsesVisibleOrderAndRemovesMissingSelectionsAndAnchor() {
        val selection = BrowserSelection()
        selection.swipe("B", true, order)
        selection.add("F")
        val refreshed = listOf("F", "D", "C", "A")
        selection.retain(refreshed)
        selection.swipe("C", true, refreshed)
        assertEquals(setOf("F", "C"), selection.selectedPaths)
        selection.swipe("F", true, refreshed)
        assertEquals(setOf("F", "D", "C"), selection.selectedPaths)
    }

    @Test fun emptyRefreshKeepsModeUntilExplicitlyCancelledAndHiddenRowsCannotBeSelected() {
        val selection = BrowserSelection()
        selection.swipe("A", true, order)
        selection.retain(emptyList())
        assertTrue(selection.inSelectionMode)
        assertTrue(selection.selectedPaths.isEmpty())
        selection.clear()
        selection.swipe("hidden", true, order)
        assertFalse(selection.inSelectionMode)
    }

    @Test fun ordinarySingleSelectionDoesNotEnterModeOrAnchorARange() {
        val selection = BrowserSelection()
        selection.selectOnly("A")
        assertFalse(selection.inSelectionMode)
        selection.swipe("D", true, order)
        assertEquals(setOf("A", "D"), selection.selectedPaths)
    }
}
