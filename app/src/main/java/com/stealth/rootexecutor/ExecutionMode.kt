package com.stealth.rootexecutor

enum class ExecutionMode(val key: String, val label: String) {
    PIPE("pipe", "管道"), PTY("pty", "PTY 伪终端");

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: PIPE
    }
}

interface ExecutionSession {
    fun run(file: ManagedFile, output: (String) -> Unit): Int
    fun send(text: String)
    fun resize(cols: Int, rows: Int) {}
    fun stop()
}
