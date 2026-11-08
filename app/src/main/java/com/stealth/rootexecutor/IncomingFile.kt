package com.stealth.rootexecutor

import java.io.File
import java.io.InputStream

/** A private, bounded snapshot: never hand a sender-controlled path to the root shell. */
internal object IncomingFile {
    fun copy(input: InputStream, destination: File, maxBytes: Long = ZipExtractor.MAX_ARCHIVE_BYTES) {
        try {
            destination.outputStream().use { output ->
                val buffer = ByteArray(32 * 1024)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(count.toLong() <= maxBytes - total) { "导入文件超过大小限制（最大 512 MiB）" }
                    output.write(buffer, 0, count)
                    total += count
                }
            }
        } catch (error: Exception) {
            destination.delete()
            throw error
        }
    }

    fun isZip(file: File, name: String, mimeType: String?): Boolean {
        val magic = file.inputStream().use { input -> ByteArray(4).also { input.read(it) } }
        return name.endsWith(".zip", true) || mimeType in setOf("application/zip", "application/x-zip-compressed") ||
            magic.contentEquals(byteArrayOf(0x50, 0x4b, 3, 4)) ||
            magic.contentEquals(byteArrayOf(0x50, 0x4b, 5, 6)) ||
            magic.contentEquals(byteArrayOf(0x50, 0x4b, 7, 8))
    }
}
