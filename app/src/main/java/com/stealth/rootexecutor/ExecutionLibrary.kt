package com.stealth.rootexecutor

internal data class ExecutionFolder(val name: String, val prefix: String, val programCount: Int)

internal object ExecutionLibrary {
    fun folders(files: List<ManagedFile>, prefix: String): List<ExecutionFolder> = files.asSequence()
        .filter { it.kind == "project" && it.name.startsWith(prefix) }
        .map { it.name.removePrefix(prefix) }.filter { '/' in it }
        .groupingBy { it.substringBefore('/') }.eachCount()
        .map { (name, count) -> ExecutionFolder(name, "$prefix$name/", count) }
        .sortedBy { it.name.lowercase() }

    fun programs(files: List<ManagedFile>, prefix: String): List<ManagedFile> = files.filter {
        if (prefix.isEmpty()) it.kind != "project"
        else it.kind == "project" && it.name.startsWith(prefix) && '/' !in it.name.removePrefix(prefix)
    }.sortedBy { it.name.lowercase() }

    fun parent(prefix: String): String = prefix.trimEnd('/').substringBeforeLast('/', "").let {
        if (it.isEmpty()) "" else "$it/"
    }
}
