package com.stealth.rootexecutor

internal enum class ImportSource(val key: String, val label: String) {
    ASK("ask", "每次询问"), BUILTIN("builtin", "内置文件管理器"),
    MT("mt", "MT 管理器"), PHONE("phone", "手机文件管理器");
    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: ASK
    }
}

internal object ProjectImport {
    private val reserved = setOf("logs", "tmp", "scripts", "binaries")
    fun defaultName(zipName: String): String = zipName.replace(Regex("(?i)\\.zip$"), "")
        .map { if (it == '/' || it == '\\' || it.isISOControl()) '_' else it }.joinToString("").trim().ifBlank { "未命名项目" }
    fun validName(name: String): Boolean = name.isNotBlank() && name.length <= 160 &&
        !name.startsWith('.') && name !in reserved && name.none { it == '/' || it == '\\' || it.isISOControl() }
    fun validRelativeProgram(path: String): Boolean {
        val parts = path.split('/')
        return parts.size >= 2 && validName(parts.first()) && parts.all {
            it.isNotBlank() && it !in setOf(".", "..") && it.length <= 255 && it.none { c -> c == '\\' || c.isISOControl() }
        }
    }
}
