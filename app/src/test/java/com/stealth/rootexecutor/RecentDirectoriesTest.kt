package com.stealth.rootexecutor

import org.junit.Assert.*
import org.junit.Test

class RecentDirectoriesTest {
    @Test fun revisitingMovesTheDirectoryToFrontWithoutDuplicates() {
        val paths = RecentDirectories.visit(listOf("/a", "/b", "/c"), "/b/")
        assertEquals(listOf("/b", "/a", "/c"), paths)
    }

    @Test fun visitsAreBoundedAndKeepTheMostRecentDirectories() {
        var paths = emptyList<String>()
        repeat(30) { paths = RecentDirectories.visit(paths, "/directory-$it") }
        assertEquals(20, paths.size)
        assertEquals("/directory-29", paths.first())
        assertEquals("/directory-10", paths.last())
    }

    @Test fun rootChinesePathsAndEquivalentSeparatorsAreNormalized() {
        assertEquals("/", RecentDirectories.normalize("///"))
        assertEquals("/storage/中文 目录", RecentDirectories.normalize("/storage//./中文 目录/"))
        assertEquals(listOf("/a"), RecentDirectories.clean(listOf("/a/", "/a", "/./a")))
    }

    @Test fun malformedPathsCannotEnterTheSavedHistory() {
        listOf("relative", "", "/a/../b", "/bad\u0000path", "/bad\npath").forEach {
            assertNull(RecentDirectories.normalize(it))
            assertEquals(listOf("/valid"), RecentDirectories.visit(listOf("/valid"), it))
        }
    }
}
