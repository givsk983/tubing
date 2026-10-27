package com.stealth.rootexecutor

import com.android.apksig.internal.apk.AndroidBinXmlParser
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class CustomPackageTest {
    private val original = "com.stealth.rootexecutor"
    private fun manifest() = javaClass.getResourceAsStream("/package-manifest.axml")!!.use { it.readBytes() }
    private fun attributes(bytes: ByteArray): List<Triple<String, String, String>> {
        val parser = AndroidBinXmlParser(ByteBuffer.wrap(bytes))
        val result = mutableListOf<Triple<String, String, String>>()
        while (parser.next() != AndroidBinXmlParser.EVENT_END_DOCUMENT) {
            if (parser.eventType == AndroidBinXmlParser.EVENT_START_ELEMENT) {
                repeat(parser.attributeCount) { index ->
                    if (parser.getAttributeValueType(index) == AndroidBinXmlParser.VALUE_TYPE_STRING)
                        result += Triple(parser.name, parser.getAttributeName(index), parser.getAttributeStringValue(index))
                }
            }
        }
        return result
    }

    @Test fun packageNamesRejectShellPathsInvalidSegmentsAndReservedNamespace() {
        listOf("", "single", ".example.app", "com..app", "com.1app", "com.app;id", "com/a", "com.a\n", "android.example", "a." + "b".repeat(200))
            .forEach { assertFalse(it, PackageManifest.validName(it)) }
        listOf("com.example.tubing", "a.b", "Com.Example_123").forEach { assertTrue(it, PackageManifest.validName(it)) }
    }

    @Test fun actualCompiledManifestRetainsComponentsPermissionsAndVersionAcrossRepeatedRenames() {
        val baseline = attributes(manifest())
        assertTrue(baseline.contains(Triple("activity", "name", "$original.MainActivity")))
        var bytes = manifest()
        var previous = original
        listOf("a.b", "com.example." + "long".repeat(40), "org.other.app").forEach { target ->
            bytes = PackageManifest.rename(bytes, previous, target)
            assertEquals(baseline.map { if (it == Triple("manifest", "package", original)) it.copy(third = target) else it }, attributes(bytes))
            assertEquals(bytes.size, ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(4))
            previous = target
        }
    }

    @Test fun wrongSourcePackageAndInvalidXmlFailClosed() {
        assertThrows(IllegalStateException::class.java) { PackageManifest.rename(manifest(), "not.the.package", "a.b") }
        assertThrows(IllegalArgumentException::class.java) { PackageManifest.rename(byteArrayOf(1, 2), original, "a.b") }
    }

    @Test fun repackingPreservesResourceAndNativeLibraryStorageAlignmentAndDropsOldSignatures() {
        val directory = kotlin.io.path.createTempDirectory("tubing-package-test").toFile()
        try {
            val source = File(directory, "source.apk")
            val output = File(directory, "output.apk")
            val names = listOf("resources.arsc", "lib/arm64-v8a/libtest.so")
            ZipOutputStream(source.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("AndroidManifest.xml")); zip.write(manifest()); zip.closeEntry()
                zip.putNextEntry(ZipEntry("META-INF/CERT.RSA")); zip.write(byteArrayOf(9)); zip.closeEntry()
                names.forEach { name ->
                    val bytes = ByteArray(65) { it.toByte() }
                    zip.putNextEntry(ZipEntry(name).apply {
                        method = ZipEntry.STORED; size = bytes.size.toLong(); crc = CRC32().apply { update(bytes) }.value
                    })
                    zip.write(bytes); zip.closeEntry()
                }
            }
            PackageArchive.rewrite(source, output, original, "org.example.clone")
            ZipFile(output).use { zip ->
                assertNull(zip.getEntry("META-INF/CERT.RSA"))
                names.forEach { assertEquals(ZipEntry.STORED, zip.getEntry(it).method) }
                assertTrue(attributes(zip.getInputStream(zip.getEntry("AndroidManifest.xml")).readBytes())
                    .contains(Triple("manifest", "package", "org.example.clone")))
            }
            val bytes = output.readBytes()
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            // Stored entries have known lengths and no data descriptor; locate local headers.
            for (offset in 0 until bytes.size - 30) {
                if (b.getInt(offset) != 0x04034b50) continue
                val nameSize = b.getShort(offset + 26).toInt() and 65535
                val extraSize = b.getShort(offset + 28).toInt() and 65535
                val name = bytes.copyOfRange(offset + 30, offset + 30 + nameSize).toString(Charsets.UTF_8)
                if (name in names) assertEquals(0, (offset + 30 + nameSize + extraSize) % if (name.endsWith(".so")) 16384 else 4)
            }
        } finally { directory.deleteRecursively() }
    }
}
