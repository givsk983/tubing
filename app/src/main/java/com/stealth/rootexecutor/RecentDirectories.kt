package com.stealth.rootexecutor

internal object RecentDirectories {
    const val LIMIT = 20

    fun normalize(path: String): String? {
        if (!path.startsWith('/') || path.any { it.isISOControl() }) return null
        val parts = path.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) return null
        return "/" + parts.joinToString("/")
    }

    fun clean(paths: List<String>): List<String> = paths.mapNotNull(::normalize).distinct().take(LIMIT)

    fun visit(paths: List<String>, path: String): List<String> {
        val normalized = normalize(path) ?: return clean(paths)
        return clean(listOf(normalized) + paths)
    }
}
