package com.stealth.rootexecutor

import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

internal object PackageArchive {
    private class Counter(output: OutputStream) : FilterOutputStream(output) {
        var count = 0L
        override fun write(value: Int) { out.write(value); count++ }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            out.write(bytes, offset, length); count += length
        }
    }

    fun rewrite(sourceApk: File, outputApk: File, old: String, target: String) {
        ZipFile(sourceApk).use { source ->
            val counter = Counter(outputApk.outputStream().buffered())
            ZipOutputStream(counter).use { output ->
                source.entries().asSequence().forEach { original ->
                    val metadata = original.name.uppercase(java.util.Locale.ROOT)
                    val signature = metadata.startsWith("META-INF/") &&
                        (metadata == "META-INF/MANIFEST.MF" || metadata.endsWith(".SF") ||
                            metadata.endsWith(".RSA") || metadata.endsWith(".DSA") || metadata.endsWith(".EC") ||
                            metadata.startsWith("META-INF/SIG-"))
                    if (signature || original.isDirectory) return@forEach
                    val entry = ZipEntry(original.name)
                    if (original.method == ZipEntry.STORED) {
                        entry.method = ZipEntry.STORED
                        entry.size = original.size
                        entry.crc = original.crc
                        val alignment = if (entry.name.startsWith("lib/") && entry.name.endsWith(".so")) 16384 else 4
                        val headerEnd = counter.count + 30 + entry.name.toByteArray(Charsets.UTF_8).size
                        var padding = ((alignment - headerEnd % alignment) % alignment).toInt()
                        if (padding in 1..3) padding += alignment
                        if (padding > 0) entry.extra = ByteArray(padding).also {
                            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN)
                                .putShort(0xd935.toShort()).putShort((padding - 4).toShort())
                        }
                    }
                    source.getInputStream(original).use { input ->
                        if (entry.name == "AndroidManifest.xml") {
                            // The string pool changes size, so do not reuse its stored CRC/size.
                            entry.method = ZipEntry.DEFLATED
                            output.putNextEntry(entry)
                            output.write(PackageManifest.rename(input.readBytes(), old, target))
                        } else {
                            output.putNextEntry(entry)
                            input.copyTo(output)
                        }
                    }
                    output.closeEntry()
                }
            }
        }
    }
}
