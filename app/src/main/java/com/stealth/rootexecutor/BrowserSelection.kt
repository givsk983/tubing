package com.stealth.rootexecutor

/** One directory's selection. Paths, rather than row positions, survive list refreshes. */
internal class BrowserSelection {
    private val paths = linkedSetOf<String>()
    val selectedPaths: Set<String> get() = paths.toSet()
    var inSelectionMode = false
        private set
    private var rightSwipeAnchor: String? = null

    fun selectOnly(path: String) {
        clear()
        paths.add(path)
    }

    fun add(path: String) {
        inSelectionMode = true
        paths.add(path)
    }

    fun toggle(path: String) {
        inSelectionMode = true
        if (!paths.add(path)) paths.remove(path)
    }

    fun swipe(path: String, right: Boolean, visiblePaths: List<String>) {
        val end = visiblePaths.indexOf(path)
        if (end < 0) return
        add(path)
        if (right) {
            val start = visiblePaths.indexOf(rightSwipeAnchor)
            if (start >= 0) paths.addAll(visiblePaths.subList(minOf(start, end), maxOf(start, end) + 1))
            rightSwipeAnchor = path
        }
    }

    fun retain(visiblePaths: List<String>) {
        paths.retainAll(visiblePaths.toSet())
        if (rightSwipeAnchor !in visiblePaths) rightSwipeAnchor = null
    }

    fun clear() {
        paths.clear()
        rightSwipeAnchor = null
        inSelectionMode = false
    }
}
