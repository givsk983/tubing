package com.stealth.rootexecutor

import org.junit.Assert.*
import org.junit.Test

class ImportOptionsTest {
    @Test fun missingOrInvalidSourceAsksEveryTime() {
        assertEquals(ImportSource.ASK, ImportSource.fromKey(null))
        assertEquals(ImportSource.ASK, ImportSource.fromKey("removed-manager"))
        ImportSource.entries.forEach { assertEquals(it, ImportSource.fromKey(it.key)) }
    }

    @Test fun projectNameDefaultsToZipNameWithoutCaseSensitiveSuffix() {
        assertEquals("中文 项目-1.2", ProjectImport.defaultName("中文 项目-1.2.ZIP"))
        assertEquals("未命名项目", ProjectImport.defaultName(".zip"))
        assertEquals("a_b", ProjectImport.defaultName("a/b.zip"))
    }

    @Test fun invalidProjectNamesCannotReplaceInternalDirectoriesOrEscapeTheLibrary() {
        listOf("", ".", "..", "../other", "/absolute", "x/y", "x\\y", ".hidden", "logs", "tmp", "scripts", "binaries", "a\nname").forEach {
            assertFalse(it, ProjectImport.validName(it))
        }
        assertTrue(ProjectImport.validName("中文 项目 (2)"))
    }

    @Test fun nestedProgramPathsPreserveResourcesButCannotEscapeOrUseSystemRoots() {
        assertTrue(ProjectImport.validRelativeProgram("中文 项目/bin/run (2).sh"))
        listOf("one.sh", "project/../escape.sh", "logs/run.sh", "project//run.sh", "project/./run.sh", "/project/run.sh").forEach {
            assertFalse(it, ProjectImport.validRelativeProgram(it))
        }
        assertEquals("/data/adb/root_executor/中文 项目/bin/run (2).sh", RootExecutor().filePath(ManagedFile("project", "中文 项目/bin/run (2).sh", "755")))
    }

    @Test fun checkboxToggleDoesNotReplaceRangeAnchorAndNeverSelectsAnArchiveImplicitly() {
        val selection = BrowserSelection()
        val order = listOf("a.zip", "b.zip", "c.zip")
        selection.toggle("a.zip")
        assertTrue(selection.inSelectionMode)
        assertEquals(setOf("a.zip"), selection.selectedPaths)
        selection.toggle("a.zip")
        assertTrue(selection.selectedPaths.isEmpty())
        selection.swipe("b.zip", true, order)
        assertEquals(setOf("b.zip"), selection.selectedPaths)
        selection.toggle("a.zip")
        selection.swipe("c.zip", true, order)
        assertEquals(order.toSet(), selection.selectedPaths)
    }

    @Test fun fileManagerProgramsKeepTheirExistingIdentityAndOriginalDirectory() {
        val root = RootExecutor()
        assertEquals("library", root.browserFile("${root.managedRoot}/run.sh").kind)
        assertEquals("scripts", root.browserFile("${root.managedRoot}/scripts/run.sh").kind)
        assertEquals("project", root.browserFile("${root.managedRoot}/project/bin/run.sh").kind)
        val external = root.browserFile("/storage/emulated/0/Download/run.sh")
        assertEquals("external", external.kind)
        assertEquals(external.name, root.filePath(external))
        val command = root.sessionCommand(external, null, "abc-123")
        assertTrue(command.contains("/storage/emulated/0/Download"))
        assertTrue(command.contains("noexec"))
    }

    @Test(expected = IllegalArgumentException::class) fun directExecutionRejectsTraversal() {
        RootExecutor().browserFile("/storage/emulated/0/../run.sh")
    }
}
