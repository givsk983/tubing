package com.stealth.rootexecutor

import java.util.concurrent.atomic.AtomicBoolean

/** A real stdin/stdout pipe session. Does not allocate a PTY or load JNA. */
class PipeSession(
    private val root: RootExecutor,
    private val token: String,
    private val launch: (String) -> Process = { command -> ProcessBuilder("su", "-c", command).redirectErrorStream(true).start() }
) : ExecutionSession {
    @Volatile private var process: Process? = null
    @Volatile private var input: PipeInput? = null
    private val stopped = AtomicBoolean(false)

    override fun run(file: ManagedFile, output: (String) -> Unit): Int {
        check(!stopped.get()) { "执行已取消" }
        val child = launch(root.sessionCommand(file, null, token))
        process = child
        if (stopped.get()) { child.destroy(); error("执行已取消") }
        input = PipeInput(
            write = { value -> child.outputStream.write(value.toByteArray(Charsets.UTF_8)); child.outputStream.flush() },
            echo = output,
            interrupt = { root.signalSession(token, "INT") },
            eof = { child.outputStream.close() }
        )
        try {
            child.inputStream.reader(Charsets.UTF_8).use { reader ->
                val chars = CharArray(4096)
                while (true) { val count = reader.read(chars); if (count < 0) break; output(String(chars, 0, count)) }
            }
            return child.waitFor()
        } finally { input = null; runCatching { child.outputStream.close() } }
    }

    override fun send(text: String) { if (!stopped.get()) input?.accept(text) }
    override fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        try { root.stopSession(token) } finally {
            runCatching { process?.outputStream?.close() }
            process?.destroy()
        }
    }
}
