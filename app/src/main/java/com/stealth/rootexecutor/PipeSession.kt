package com.stealth.rootexecutor

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** A real stdin/stdout pipe session. Does not allocate a PTY or load JNA. */
class PipeSession(
    private val root: RootExecutor,
    private val token: String,
    private val launch: (String) -> Process = { command -> ProcessBuilder("su", "-c", command).redirectErrorStream(true).start() }
) : ExecutionSession {
    @Volatile private var process: Process? = null
    @Volatile private var input: PipeInput? = null
    private val stopped = AtomicBoolean(false)
    override val inputReady: Boolean get() = input != null && process?.isAlive == true

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
        val closingOutput = AtomicBoolean(false)
        val readerFailure = AtomicReference<IOException?>(null)
        try {
            // Drain output concurrently with waiting for the command. Waiting for EOF first can
            // hang forever when a script leaves a background process holding the inherited pipe.
            val reader = Thread {
                try {
                    child.inputStream.reader(Charsets.UTF_8).use { stream ->
                        val chars = CharArray(4096)
                        while (true) {
                            val count = stream.read(chars)
                            if (count < 0) break
                            output(String(chars, 0, count))
                        }
                    }
                } catch (e: IOException) {
                    // Closing a pipe from the execution thread interrupts a blocked read on Android.
                    // Expected shutdown must not escape this thread and crash the app process.
                    if (!closingOutput.get() && !stopped.get()) readerFailure.set(e)
                }
            }.apply { name = "tubing-pipe-output"; isDaemon = true; start() }

            val code = child.waitFor()
            // A descendant can inherit stdout after the command has exited. Give buffered output
            // a moment to drain, then close our side so that the session can finish promptly.
            reader.join(250)
            if (reader.isAlive) {
                closingOutput.set(true)
                runCatching { child.inputStream.close() }
                reader.join(1_000)
            }
            readerFailure.get()?.let { throw it }
            return code
        } finally { closingOutput.set(true); input = null; runCatching { child.outputStream.close() }; runCatching { child.inputStream.close() } }
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
