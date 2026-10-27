package com.stealth.rootexecutor

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Rebuild only the binary XML string pool, keeping resource IDs and class names intact. */
internal object PackageManifest {
    fun validName(name: String) = name.length <= 200 &&
        Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+").matches(name) &&
        !name.startsWith("android.") && name != "android"

    fun rename(xml: ByteArray, old: String, replacement: String): ByteArray {
        require(validName(replacement)) { "包名格式无效" }
        val input = ByteBuffer.wrap(xml).order(ByteOrder.LITTLE_ENDIAN)
        require(xml.size >= 8 && input.getShort(0).toInt() == 3 && input.getInt(4) == xml.size)
        var offset = input.getShort(2).toInt() and 0xffff
        while (offset + 8 <= xml.size) {
            val size = input.getInt(offset + 4)
            require(size >= 8 && size <= xml.size - offset)
            if (input.getShort(offset).toInt() == 1) {
                val pool = rebuildPool(xml.copyOfRange(offset, offset + size), old, replacement)
                return (xml.copyOfRange(0, offset) + pool + xml.copyOfRange(offset + size, xml.size)).also {
                    ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(4, it.size)
                }
            }
            offset += size
        }
        error("安装包缺少 Manifest 字符串表")
    }

    private fun rebuildPool(pool: ByteArray, old: String, replacement: String): ByteArray {
        val b = ByteBuffer.wrap(pool).order(ByteOrder.LITTLE_ENDIAN)
        val headerSize = b.getShort(2).toInt() and 0xffff
        val count = b.getInt(8)
        val styles = b.getInt(12)
        val flags = b.getInt(16)
        val start = b.getInt(20)
        val styleStart = b.getInt(24)
        require(headerSize >= 28 && count >= 0 && styles >= 0 && start <= pool.size)
        require(count.toLong() * 4 + styles.toLong() * 4 + headerSize <= start)
        val utf8 = flags and 0x100 != 0
        val data = ByteArrayOutputStream()
        val offsets = IntArray(count)
        var changed = false
        fun length8(): Int {
            val first = b.get().toInt() and 255
            return if (first and 128 == 0) first else ((first and 127) shl 8) or (b.get().toInt() and 255)
        }
        fun length16(): Int {
            val first = b.short.toInt() and 65535
            return if (first and 32768 == 0) first else ((first and 32767) shl 16) or (b.short.toInt() and 65535)
        }
        fun write8(value: Int) {
            require(value <= 32767)
            if (value > 127) data.write((value shr 8) or 128)
            data.write(value and 255)
        }
        fun write16(value: Int) { data.write(value and 255); data.write((value shr 8) and 255) }
        repeat(count) { index ->
            b.position(start + b.getInt(headerSize + index * 4))
            val length = if (utf8) { length8(); length8() } else length16() * 2
            val bytes = ByteArray(length).also { b.get(it) }
            val value = bytes.toString(if (utf8) Charsets.UTF_8 else Charsets.UTF_16LE)
            val updated = if (value == old) { changed = true; replacement } else value
            offsets[index] = data.size()
            val encoded = updated.toByteArray(if (utf8) Charsets.UTF_8 else Charsets.UTF_16LE)
            if (utf8) { write8(updated.length); write8(encoded.size) }
            else {
                if (updated.length > 32767) write16((updated.length shr 16) or 32768)
                write16(updated.length)
            }
            data.write(encoded); data.write(0)
            if (!utf8) data.write(0)
        }
        check(changed) { "安装包包名与当前应用不匹配" }
        while (data.size() % 4 != 0) data.write(0)
        val styleBytes = if (styleStart == 0) byteArrayOf() else pool.copyOfRange(styleStart, pool.size)
        val result = pool.copyOfRange(0, start) + data.toByteArray() + styleBytes
        ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(4, result.size)
            putInt(16, flags and 1.inv()) // A replacement may change lexicographic ordering.
            putInt(24, if (styleStart == 0) 0 else start + data.size())
            offsets.forEachIndexed { index, value -> putInt(headerSize + index * 4, value) }
        }
        return result
    }
}
