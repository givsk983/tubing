package com.stealth.rootexecutor

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/** Execute the production file transaction against isolated real files using a POSIX host shell. */
class ShellScriptStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var bash: File
    private lateinit var fixture: File
    private var failBackup = false
    private var failRename = false

    @Before fun setup() {
        val candidates = listOf(File("/bin/bash")) + System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .map { File(it).parentFile?.let { parent -> File(parent, "bin/bash.exe") } }
            .filterNotNull()
        val found = candidates.firstOrNull { it.isFile }
        assumeTrue("A POSIX shell is needed for real filesystem transaction tests", found != null)
        bash = found!!
        fixture = temporary.newFolder("editor fixtures 中文")
    }

    private fun posix(file: File): String = file.canonicalPath.replace('\\', '/').let {
        if (it.length > 2 && it[1] == ':') "/${it[0].lowercaseChar()}${it.substring(2)}" else it
    }

    private fun shell(command: String): CommandResult {
        val process = ProcessBuilder(bash.absolutePath, "-s").redirectErrorStream(true).start()
        process.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(command); it.newLine() }
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
        check(process.waitFor(20, TimeUnit.SECONDS)) { "Shell test timed out" }
        return CommandResult(process.exitValue(), output)
    }

    private fun store() = ShellScriptStore(execute = { command, _, _ ->
        val faults = (if (failBackup) "cp() { case \"${'$'}*\" in *.tubing-backup-*) echo '模拟备份失败'; return 42;; esac; command cp \"${'$'}@\"; };\n" else "") +
            (if (failRename) "mv() { echo '模拟替换失败'; return 43; };\n" else "")
        shell(faults + command.replace(fixture.absolutePath, posix(fixture)))
    })

    private fun file(name: String = "test.sh", value: String = "#!/system/bin/sh\necho original\n") = File(fixture, name).apply { writeText(value) }
    private fun backups() = fixture.listFiles()!!.filter { it.name.startsWith(".tubing-backup-") }

    @Test fun readsEmptyAndQuotedUnicodePathsWithoutExecutingContents() {
        val store = store()
        val script = file("中文 ' ${'$'}(touch SHOULD_NOT_EXIST).SH", "echo '${'$'}HOME'\n")
        assertEquals(script.readText(), store.read(posix(script)).text)
        assertEquals("", store.read(posix(file("empty.sh", ""))).text)
        assertFalse(File(fixture, "SHOULD_NOT_EXIST").exists())
    }

    @Test fun savesAtomicallyWithExactBackupPermissionsAndRepeatableSave() {
        val script = file("中文 ' script.SH", "\uFEFF#!/system/bin/sh\r\necho 原文")
        val store = store()
        val original = script.readBytes()
        val beforeMode = shell("stat -c '%a:%u:%g' ${RootExecutor().quote(posix(script))}").output
        val doc = store.read(posix(script))
        val first = store.save(doc, doc.text.replace("原文", "修改"), fixture)
        assertArrayEquals(first.document.encode(first.document.text), script.readBytes())
        assertEquals(1, backups().size)
        assertArrayEquals(original, backups().single().readBytes())
        assertTrue(first.backupPath.endsWith(backups().single().name))
        assertEquals(beforeMode, shell("stat -c '%a:%u:%g' ${RootExecutor().quote(posix(script))}").output)
        val secondOriginal = script.readBytes()
        store.save(first.document, first.document.text + "\necho second", fixture)
        assertEquals(2, backups().size)
        assertTrue(backups().any { it.readBytes().contentEquals(secondOriginal) })
        assertFalse(fixture.listFiles()!!.any { it.name.startsWith(".tubing-edit.") || it.name.startsWith("sh-editor-") })
    }

    @Test fun detectsExternalChangesEvenWhenLengthIsUnchangedAndKeepsOriginal() {
        val script = file(value = "echo one\n")
        val store = store()
        val doc = store.read(posix(script))
        script.writeText("echo two\n")
        val failure = runCatching { store.save(doc, "echo draft\n", fixture) }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("其他工具修改"))
        assertEquals("echo two\n", script.readText())
        assertTrue(backups().isEmpty())
    }

    @Test fun failedBackupNeverOverwritesOriginalOrLeavesAnIncompleteBackup() {
        val script = file()
        val store = store()
        val doc = store.read(posix(script))
        failBackup = true
        val failure = runCatching { store.save(doc, doc.text.replace("original", "changed"), fixture) }.exceptionOrNull()
        assertTrue(failure?.message, failure?.message.orEmpty().contains("模拟备份失败"))
        assertEquals(doc.text, script.readText())
        assertTrue(backups().isEmpty())
        assertEquals(listOf(script), fixture.listFiles()!!.toList())
    }

    @Test fun failedReplacementKeepsVerifiedBackupAndOriginal() {
        val script = file()
        val store = store()
        val doc = store.read(posix(script))
        failRename = true
        val failure = runCatching { store.save(doc, doc.text.replace("original", "changed"), fixture) }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.message, failure.message.orEmpty().contains("备份："))
        assertEquals(doc.text, script.readText())
        assertArrayEquals(script.readBytes(), backups().single().readBytes())
        assertFalse(fixture.listFiles()!!.any { it.name.startsWith(".tubing-edit.") })
    }

    @Test fun rejectsWrongExtensionBinaryAndDeletedFiles() {
        val store = store()
        assertTrue(runCatching { store.read(posix(file("file.txt"))) }.isFailure)
        assertTrue(runCatching { store.read(posix(file("binary.sh", "\u007fELF\u0000"))) }.isFailure)
        val script = file()
        val doc = store.read(posix(script))
        assertTrue(script.delete())
        assertTrue(runCatching { store.save(doc, "new", fixture) }.isFailure)
        assertFalse(script.exists())
    }
}
