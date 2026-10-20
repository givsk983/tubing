package com.stealth.rootexecutor

import java.io.File
import java.nio.charset.Charset
import java.util.zip.CRC32
import java.util.zip.ZipFile

internal data class ZipItem(val path: String, val directory: Boolean, val size: Long) {
    val name: String get() = path.substringAfterLast('/')
}

internal object ZipExtractor {
    const val MAX_ARCHIVE_BYTES = 512L * 1024 * 1024
    private const val MAX_OUTPUT_BYTES = 1024L * 1024 * 1024
    private const val MAX_ENTRIES = 10_000

    private fun open(archive: File): ZipFile = try { ZipFile(archive, Charsets.UTF_8) }
        catch (_: java.util.zip.ZipException) { ZipFile(archive, Charset.forName("GB18030")) }
        catch (_: IllegalArgumentException) { ZipFile(archive, Charset.forName("GB18030")) }

    private fun entries(zip: ZipFile, limit: Int): List<Pair<java.util.zip.ZipEntry, ZipItem>> {
        val entries = zip.entries().asSequence().take(limit + 1).toList()
        require(entries.size <= limit) { "ZIP 条目过多（最多 $limit 项）" }
        val result = entries.map { entry ->
            val name = entry.name.replace('\\', '/')
            require(name.isNotBlank() && !name.startsWith('/') && !name.contains('\u0000') &&
                name.length <= 4096 && name.split('/').size <= 128 &&
                !Regex("^[A-Za-z]:").containsMatchIn(name) && name.trimEnd('/').split('/').none { it.isEmpty() || it == ".." || it == "." }) {
                "ZIP 包含不安全路径：${entry.name}"
            }
            entry to ZipItem(name.trimEnd('/'), entry.isDirectory || name.endsWith('/'), entry.size)
        }
        val paths = result.associate { it.second.path to it.second }
        require(paths.size == result.size) { "ZIP 包含重复路径" }
        val allPaths = paths.keys.toMutableSet()
        result.forEach { (_, item) ->
            var parent = item.path.substringBeforeLast('/', "")
            while (parent.isNotEmpty()) {
                require(paths[parent]?.directory != false) { "ZIP 文件与目录重名" }
                allPaths.add(parent)
                require(allPaths.size <= limit) { "ZIP 文件和目录数量过多" }
                parent = parent.substringBeforeLast('/', "")
            }
        }
        return result
    }

    fun inspect(archive: File): List<ZipItem> = open(archive).use { zip ->
        val items = linkedMapOf<String, ZipItem>()
        entries(zip, MAX_ENTRIES).forEach { (_, item) ->
            items[item.path] = item
            var parent = item.path.substringBeforeLast('/', "")
            while (parent.isNotEmpty()) {
                items.putIfAbsent(parent, ZipItem(parent, true, 0))
                parent = parent.substringBeforeLast('/', "")
            }
        }
        items.values.sortedWith(compareBy<ZipItem> { !it.directory }.thenBy { it.path.lowercase() })
    }

    fun preview(archive: File, item: ZipItem): String = open(archive).use { zip ->
        val entry = entries(zip, MAX_ENTRIES).firstOrNull { it.second.path == item.path && !it.second.directory }?.first
            ?: error("压缩包内文件不存在")
        val bytes = zip.getInputStream(entry).use { input ->
            val data = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (data.size() < 65_537) {
                val count = input.read(buffer, 0, minOf(buffer.size, 65_537 - data.size()))
                if (count < 0) break
                data.write(buffer, 0, count)
            }
            data.toByteArray()
        }
        val shown = bytes.take(65_536).toByteArray()
        if (shown.any { it == 0.toByte() }) {
            "二进制文件 · ${item.size} 字节\n前 256 字节（十六进制）：\n" +
                shown.take(256).chunked(16).joinToString("\n") { row -> row.joinToString(" ") { "%02X".format(it.toInt() and 255) } }
        } else {
            val text = runCatching { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(shown)).toString() }
                .getOrElse { shown.toString(Charset.forName("GB18030")) }
            text + if (bytes.size > 65_536) "\n\n…仅预览前 64 KiB，解压可获得完整文件。" else ""
        }
    }

    fun extract(archive: File, destination: File, maxBytes: Long = MAX_OUTPUT_BYTES, maxEntries: Int = MAX_ENTRIES,
                selection: Set<String>? = null): Int {
        require(!destination.exists()) { "解压目录已存在" }
        val zip = open(archive)
        try {
            zip.use {
                val all = entries(zip, maxEntries)
                val roots = selection?.filter { path -> selection.none { other -> path != other && path.startsWith("$other/") } }
                if (roots != null) {
                    require(roots.isNotEmpty() && roots.all { selected -> all.any { (_, item) -> item.path == selected || item.path.startsWith("$selected/") } }) { "请选择有效的 ZIP 条目" }
                    require(roots.map { it.substringAfterLast('/') }.distinct().size == roots.size) { "所选条目名称相同，请分开解压" }
                }
                val entries = all.mapNotNull { (entry, item) ->
                    val selected = roots?.firstOrNull { item.path == it || item.path.startsWith("$it/") }
                    if (roots != null && selected == null) null
                    else entry to if (selected == null) item.path else selected.substringAfterLast('/') + item.path.removePrefix(selected)
                }
                val root = destination.canonicalFile
                val targets = entries.map { (_, name) ->
                    File(root, name).canonicalFile.also { target ->
                        require(target.path.startsWith(root.path + File.separator)) { "ZIP 路径超出解压目录" }
                    }
                }
                require(targets.distinct().size == targets.size) { "ZIP 包含重复路径" }
                check(destination.mkdirs()) { "无法创建解压临时目录" }
                var total = 0L
                var files = 0
                val buffer = ByteArray(32 * 1024)
                entries.zip(targets).forEach { (pair, target) ->
                    val entry = pair.first
                    if (entry.isDirectory || entry.name.endsWith('\\')) {
                        check(target.isDirectory || target.mkdirs()) { "ZIP 文件与目录重名" }
                    } else {
                        require(entry.size <= maxBytes - total) { "解压内容超过大小限制" }
                        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) { "无法创建解压子目录" }
                        check(target.createNewFile()) { "ZIP 文件与目录重名" }
                        val crc = CRC32()
                        var size = 0L
                        zip.getInputStream(entry).use { input ->
                            target.outputStream().use { output ->
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count == -1) break
                                    require(count.toLong() <= maxBytes - total) { "解压内容超过大小限制" }
                                    total += count; size += count; crc.update(buffer, 0, count)
                                    output.write(buffer, 0, count)
                                }
                            }
                        }
                        check(size == entry.size && crc.value == entry.crc) { "ZIP 数据损坏：${entry.name}" }
                        files++
                    }
                }
                return files
            }
        } catch (error: Exception) {
            destination.deleteRecursively()
            throw error
        }
    }
}
