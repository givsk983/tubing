package com.stealth.rootexecutor

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectUpdateTest {
    @get:Rule val temporary = TemporaryFolder()
    private val projectName = "项目 ' sample"

    private fun posix(file: File): String = file.canonicalPath.replace('\\', '/').let {
        if (it.length > 2 && it[1] == ':') "/${it[0].lowercaseChar()}${it.substring(2)}" else it
    }

    private fun update(library: File, source: File, prefix: String = ""): CommandResult {
        val bash = (listOf(File("/bin/bash")) + System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .mapNotNull { File(it).parentFile?.resolve("bin/bash.exe") }).firstOrNull { it.isFile }
        assumeTrue("A POSIX shell is needed for project update tests", bash != null)
        val command = RootExecutor().projectImportCommand(posix(library), source, projectName, update = true)
            .replace(source.absolutePath, posix(source))
        val process = ProcessBuilder(bash!!.absolutePath, "-s").redirectErrorStream(true).start()
        process.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write("$prefix\n$command\n") }
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
        check(process.waitFor(20, TimeUnit.SECONDS)) { "Project update test timed out" }
        return CommandResult(process.exitValue(), output)
    }

    @Test fun overlaysByRelativePathAndKeepsUnlistedConfiguration() {
        val library = temporary.newFolder("library")
        val target = File(library, projectName).apply { mkdir() }
        File(target, "bin").mkdir()
        File(target, "bin/run.sh").writeText("old")
        File(target, "settings.ini").writeText("local config")
        File(target, "run.sh").writeText("different directory")
        val source = temporary.newFolder("incoming")
        File(source, "bin").mkdir()
        File(source, "bin/run.sh").writeText("new")
        File(source, "bin/new.sh").writeText("added")

        val result = update(library, source)

        assertEquals(result.output, 0, result.code)
        assertEquals("new", File(target, "bin/run.sh").readText())
        assertEquals("added", File(target, "bin/new.sh").readText())
        assertEquals("local config", File(target, "settings.ini").readText())
        assertEquals("different directory", File(target, "run.sh").readText())
        assertEquals(listOf(projectName), library.list()!!.toList())
    }

    @Test fun fileDirectoryConflictLeavesExistingProjectIntact() {
        val library = temporary.newFolder("library")
        val target = File(library, projectName).apply { mkdir() }
        File(target, "conflict").mkdir()
        File(target, "conflict/settings.ini").writeText("local config")
        val source = temporary.newFolder("incoming")
        File(source, "conflict").writeText("file")
        File(source, "new.sh").writeText("added")

        val result = update(library, source)

        assertTrue(result.output, result.code != 0 && result.output.contains("项目路径冲突"))
        assertEquals("local config", File(target, "conflict/settings.ini").readText())
        assertFalse(File(target, "new.sh").exists())
        assertEquals(listOf(projectName), library.list()!!.toList())
    }

    @Test fun failedPublishRestoresOriginalProject() {
        val library = temporary.newFolder("library")
        val target = File(library, projectName).apply { mkdir() }
        File(target, "run.sh").writeText("old")
        val source = temporary.newFolder("incoming")
        File(source, "run.sh").writeText("new")
        val failure = """mv() { case "${'$'}3" in */merged) return 41;; esac; command mv "${'$'}@"; }"""

        val result = update(library, source, failure)

        assertEquals(result.output, 41, result.code)
        assertEquals("old", File(target, "run.sh").readText())
        assertEquals(listOf(projectName), library.list()!!.toList())
    }
}
