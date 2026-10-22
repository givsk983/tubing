package com.stealth.rootexecutor

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.Charset
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipExtractorTest {
    @get:Rule val temp = TemporaryFolder()

    private fun archive(vararg entries: Pair<String, String>, charset: Charset = Charsets.UTF_8, stored: Boolean = false): File {
        val file = temp.newFile()
        ZipOutputStream(file.outputStream(), charset).use { zip ->
            entries.forEach { (name, text) ->
                val bytes = text.toByteArray()
                val entry = ZipEntry(name)
                if (stored) {
                    entry.method = ZipEntry.STORED; entry.size = bytes.size.toLong()
                    entry.crc = CRC32().apply { update(bytes) }.value
                }
                zip.putNextEntry(entry); zip.write(bytes); zip.closeEntry()
            }
        }
        return file
    }

    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected extraction failure") } catch (_: Exception) { }
    }

    @Test fun extractsNestedChineseNamesSpacesAndEmptyFolders() {
        val zip = archive("空目录/" to "", "中文 空格/脚本.sh" to "echo 你好", "empty" to "")
        val output = File(temp.root, "output")
        assertEquals(2, ZipExtractor.extract(zip, output))
        assertEquals("echo 你好", File(output, "中文 空格/脚本.sh").readText())
        assertTrue(File(output, "空目录").isDirectory)
        assertTrue(File(output, "empty").isFile)
    }

    @Test fun decodesLegacyChineseZipNames() {
        val zip = archive("中文/测试.txt" to "内容", charset = Charset.forName("GB18030"))
        val output = File(temp.root, "legacy")
        ZipExtractor.extract(zip, output)
        assertEquals("内容", File(output, "中文/测试.txt").readText())
    }

    @Test fun supportsWindowsDirectorySeparators() {
        val output = File(temp.root, "windows")
        ZipExtractor.extract(archive("folder\\" to "", "folder\\file.txt" to "hello"), output)
        assertEquals("hello", File(output, "folder/file.txt").readText())
    }

    @Test fun blocksTraversalAndAbsolutePathsBeforeWriting() {
        listOf("../escape", "sub/../../escape", "/absolute", "C:/drive", "..\\escape", "\\absolute", "sub/./file").forEachIndexed { index, name ->
            val output = File(temp.root, "blocked$index")
            rejects { ZipExtractor.extract(archive("valid.txt" to "ok", name to "bad"), output) }
            assertFalse(output.exists())
        }
        assertFalse(File(temp.root, "escape").exists())
    }

    @Test fun refusesExistingDestinationWithoutChangingIt() {
        val output = temp.newFolder()
        File(output, "keep").writeText("original")
        rejects { ZipExtractor.extract(archive("keep" to "replacement"), output) }
        assertEquals("original", File(output, "keep").readText())
    }

    @Test fun rejectsNormalizedDuplicatePathsAndFileDirectoryCollisions() {
        listOf(archive("a/b" to "first", "a\\b" to "second"),
            archive("a" to "file", "a/b" to "nested")).forEachIndexed { index, zip ->
            val output = File(temp.root, "collision$index")
            rejects { ZipExtractor.extract(zip, output) }
            assertFalse(output.exists())
        }
    }

    @Test fun enforcesByteAndEntryLimitsAndCleansPartialOutput() {
        val zip = archive("one" to "1234", "two" to "5678")
        val output = File(temp.root, "limited")
        rejects { ZipExtractor.extract(zip, output, maxBytes = 5) }
        assertFalse(output.exists())
        rejects { ZipExtractor.extract(zip, output, maxEntries = 1) }
        assertFalse(output.exists())
    }

    @Test fun rejectsBadCrcAndNonZipInput() {
        val zip = archive("file" to "unique_payload", stored = true)
        val bytes = zip.readBytes()
        val payload = "unique_payload".toByteArray()
        val offset = (0..bytes.size - payload.size).first { start -> payload.indices.all { bytes[start + it] == payload[it] } }
        bytes[offset] = 'X'.code.toByte(); zip.writeBytes(bytes)
        val output = File(temp.root, "corrupt")
        rejects { ZipExtractor.extract(zip, output) }
        assertFalse(output.exists())
        val invalid = temp.newFile().apply { writeText("not a zip") }
        rejects { ZipExtractor.extract(invalid, output) }
        assertFalse(output.exists())
    }

    @Test fun emptyZipProducesEmptyFolder() {
        val output = File(temp.root, "emptyZip")
        assertEquals(0, ZipExtractor.extract(archive(), output))
        assertTrue(output.isDirectory)
    }

    @Test fun inspectionIncludesImplicitDirectoriesAndDoesNotExtractFiles() {
        val zip = archive("中文/nested/run.sh" to "echo hello", "root.txt" to "text")
        val items = ZipExtractor.inspect(zip)
        assertEquals(setOf("中文", "中文/nested", "中文/nested/run.sh", "root.txt"), items.map { it.path }.toSet())
        assertTrue(items.first { it.path == "中文/nested" }.directory)
        assertFalse(File(temp.root, "中文").exists())
    }

    @Test fun selectedFilesAreExtractedDirectlyWithoutUnselectedSiblings() {
        val zip = archive("a/run.sh" to "shell", "a/other" to "skip", "b/data" to "include")
        val output = File(temp.root, "selected")
        assertEquals(2, ZipExtractor.extract(zip, output, selection = setOf("a/run.sh", "b/data")))
        assertEquals(setOf("run.sh", "data"), output.list()!!.toSet())
        assertEquals("shell", File(output, "run.sh").readText())
    }

    @Test fun selectedFoldersKeepSubtreesAndIgnoreRedundantChildSelections() {
        val zip = archive("a/nested/run.sh" to "shell", "b/other" to "skip")
        val output = File(temp.root, "folderSelection")
        assertEquals(1, ZipExtractor.extract(zip, output, selection = setOf("a", "a/nested/run.sh")))
        assertEquals("shell", File(output, "a/nested/run.sh").readText())
        assertFalse(File(output, "b").exists())
    }

    @Test fun rejectsMissingEmptyAndAmbiguousSelections() {
        val zip = archive("a/file" to "one", "b/file" to "two")
        listOf(emptySet(), setOf("missing"), setOf("a/file", "b/file"), setOf("../escape")).forEachIndexed { index, selected ->
            val output = File(temp.root, "invalidSelection$index")
            rejects { ZipExtractor.extract(zip, output, selection = selected) }
            assertFalse(output.exists())
        }
    }

    @Test fun previewsTextAndLimitsLargeContent() {
        val zip = archive("script.sh" to "echo 你好", "large.txt" to "x".repeat(80_000), "bin" to "\u0000\u0001\u0002")
        val items = ZipExtractor.inspect(zip)
        assertEquals("echo 你好", ZipExtractor.preview(zip, items.first { it.path == "script.sh" }))
        val large = ZipExtractor.preview(zip, items.first { it.path == "large.txt" })
        assertTrue(large.startsWith("x".repeat(65_536)))
        assertTrue(large.length < 66_000)
        assertTrue(large.contains("64 KiB"))
        assertTrue(ZipExtractor.preview(zip, items.first { it.path == "bin" }).contains("00 01 02"))
    }

    @Test fun inspectionRejectsUnsafeAndCollidingNames() {
        rejects { ZipExtractor.inspect(archive("../escape" to "bad")) }
        rejects { ZipExtractor.inspect(archive("a" to "file", "a/b" to "nested")) }
        rejects { ZipExtractor.inspect(archive("a//file" to "bad")) }
    }

    @Test fun selectedScriptsAndBinariesKeepTheirContentsAndImportKinds() {
        val binary = "\u007fELF\u0000\u0001\u0002"
        val zip = archive("脚本/运行.SH" to "#!/system/bin/sh\necho hello", "arm64/tool" to binary,
            "readme.txt" to "skip", "other/unused.sh" to "skip")
        val output = File(temp.root, "imported")
        assertEquals(2, ZipExtractor.extract(zip, output, selection = setOf("脚本/运行.SH", "arm64/tool")))
        assertEquals(setOf("运行.SH", "tool"), output.list()!!.toSet())
        assertEquals("scripts", ZipExtractor.importKind(File(output, "运行.SH")))
        assertEquals("binaries", ZipExtractor.importKind(File(output, "tool")))
        assertArrayEquals(binary.toByteArray(), File(output, "tool").readBytes())
    }

    @Test fun importClassificationChecksElfMagicAndRejectsOtherFiles() {
        val file = File(temp.root, "program.bin")
        file.writeText("not an ELF")
        rejects { ZipExtractor.importKind(file) }
        file.writeBytes(byteArrayOf(127, 69, 76))
        rejects { ZipExtractor.importKind(file) }
        file.writeBytes(byteArrayOf(127, 69, 76, 70, 0))
        assertEquals("binaries", ZipExtractor.importKind(file))
        val disguised = File(temp.root, "program.sh").apply { writeBytes(file.readBytes()) }
        assertEquals("binaries", ZipExtractor.importKind(disguised))
        val empty = File(temp.root, "empty.txt").apply { writeText("") }
        rejects { ZipExtractor.importKind(empty) }
    }
}
