package com.stealth.rootexecutor

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files

class IncomingFileTest {
    private fun fixture(test: (File) -> Unit) {
        val directory = Files.createTempDirectory("incoming-test").toFile()
        try { test(File(directory, "snapshot")) } finally { directory.deleteRecursively() }
    }

    @Test fun boundedCopyRetainsEveryByteAtTheLimit() = fixture { file ->
        val bytes = ByteArray(65_537) { it.toByte() }
        IncomingFile.copy(bytes.inputStream(), file, bytes.size.toLong())
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun oversizedFileLeavesNoPartialSnapshot() = fixture { file ->
        assertThrows(IllegalArgumentException::class.java) { IncomingFile.copy(ByteArray(17).inputStream(), file, 16) }
        assertFalse(file.exists())
    }

    @Test fun providerReadFailureRemovesPartialFile() = fixture { file ->
        val failing = object : InputStream() {
            private var reads = 0
            override fun read(): Int = if (reads++ < 10) 65 else throw IOException("permission revoked")
        }
        assertThrows(IOException::class.java) { IncomingFile.copy(failing, file) }
        assertFalse(file.exists())
    }

    @Test fun zipHeadersWorkWithoutNameOrMimeType() = fixture { file ->
        for (header in listOf(byteArrayOf(80, 75, 3, 4), byteArrayOf(80, 75, 5, 6), byteArrayOf(80, 75, 7, 8))) {
            file.writeBytes(header)
            assertTrue(IncomingFile.isZip(file, "download", "application/octet-stream"))
        }
    }

    @Test fun zipNameAndMimeStillGoThroughArchiveValidation() = fixture { file ->
        file.writeText("not an archive")
        assertTrue(IncomingFile.isZip(file, "项目.ZIP", null))
        assertTrue(IncomingFile.isZip(file, "download", "application/zip"))
        assertThrows(Exception::class.java) { ZipExtractor.inspect(file) }
    }

    @Test fun sharedElfDoesNotNeedAnExtension() = fixture { file ->
        file.writeBytes(byteArrayOf(127, 69, 76, 70))
        assertFalse(IncomingFile.isZip(file, "run", "application/octet-stream"))
        assertEquals("binaries", ZipExtractor.importKind("run", file.readBytes()))
    }

    @Test fun scriptsAndUnsupportedFilesKeepExistingValidation() = fixture { file ->
        file.writeText("echo hello")
        assertFalse(IncomingFile.isZip(file, "run.SH", "text/plain"))
        assertEquals("scripts", ZipExtractor.importKind("run.SH", file.readBytes().copyOf(4)))
        assertThrows(IllegalStateException::class.java) { ZipExtractor.importKind("photo.jpg", file.readBytes().copyOf(4)) }
    }
}
