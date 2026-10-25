package com.stealth.rootexecutor

import org.junit.Assert.*
import org.junit.Test

class ExecutionLibraryTest {
    private val files = listOf(
        ManagedFile("library", "standalone.sh", "777"),
        ManagedFile("scripts", "old.sh", "777"),
        ManagedFile("project", "项目 A/main.sh", "755"),
        ManagedFile("project", "项目 A/bin/run", "755"),
        ManagedFile("project", "项目 A/bin/tools/helper.sh", "755"),
        ManagedFile("project", "项目 AB/main.sh", "755")
    )

    @Test fun libraryShowsEachProjectOnceAndKeepsStandaloneAndLegacyPrograms() {
        assertEquals(listOf(ExecutionFolder("项目 A", "项目 A/", 3), ExecutionFolder("项目 AB", "项目 AB/", 1)), ExecutionLibrary.folders(files, ""))
        assertEquals(setOf("old.sh", "standalone.sh"), ExecutionLibrary.programs(files, "").map { it.name }.toSet())
    }

    @Test fun enteringAProjectShowsOnlyDirectProgramsAndNextLevelFolders() {
        assertEquals(listOf(ExecutionFolder("bin", "项目 A/bin/", 2)), ExecutionLibrary.folders(files, "项目 A/"))
        assertEquals(listOf("项目 A/main.sh"), ExecutionLibrary.programs(files, "项目 A/").map { it.name })
        assertEquals(listOf("项目 A/bin/run"), ExecutionLibrary.programs(files, "项目 A/bin/").map { it.name })
    }

    @Test fun nestedNavigationDoesNotMixProjectsWithSimilarNames() {
        assertEquals("项目 A/bin/", ExecutionLibrary.parent("项目 A/bin/tools/"))
        assertEquals("", ExecutionLibrary.parent("项目 A/"))
        assertEquals(listOf("项目 A/bin/tools/helper.sh"), ExecutionLibrary.programs(files, "项目 A/bin/tools/").map { it.name })
        assertTrue(ExecutionLibrary.folders(files, "项目 A/bin/tools/").isEmpty())
        assertTrue(ExecutionLibrary.programs(files, "missing/").isEmpty())
    }
}
