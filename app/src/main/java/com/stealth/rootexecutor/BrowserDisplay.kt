package com.stealth.rootexecutor

internal enum class BrowserSort(val key: String, val label: String) {
    NAME_ASC("nameAsc", "名称 · A → Z"),
    NAME_DESC("nameDesc", "名称 · Z → A"),
    NEWEST("newest", "修改时间 · 最新优先"),
    OLDEST("oldest", "修改时间 · 最早优先"),
    LARGEST("largest", "大小 · 从大到小"),
    SMALLEST("smallest", "大小 · 从小到大");

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: NAME_ASC
    }
}

internal object BrowserDisplay {
    fun entries(entries: List<BrowserEntry>, query: String, sort: BrowserSort): List<BrowserEntry> {
        val order: Comparator<BrowserEntry> = when (sort) {
            BrowserSort.NAME_ASC -> compareBy<BrowserEntry> { it.name.lowercase(java.util.Locale.ROOT) }
            BrowserSort.NAME_DESC -> compareByDescending { it.name.lowercase(java.util.Locale.ROOT) }
            BrowserSort.NEWEST -> compareByDescending { it.modified }
            BrowserSort.OLDEST -> compareBy { it.modified }
            BrowserSort.LARGEST -> compareByDescending { it.size }
            BrowserSort.SMALLEST -> compareBy { it.size }
        }
        return entries.filter { it.name.contains(query.trim(), ignoreCase = true) }
            .sortedWith(compareBy<BrowserEntry> { it.type != "d" }.then(order)
                .thenBy { it.name.lowercase(java.util.Locale.ROOT) }.thenBy { it.path })
    }
}
