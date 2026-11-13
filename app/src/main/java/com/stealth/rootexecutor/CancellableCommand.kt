package com.stealth.rootexecutor

import java.util.concurrent.TimeUnit

/** Read-only queries own a process group. Closing stdin cancels that entire group as root. */
internal class CancellableCommand(
    private val launch: (String) -> Process = { ProcessBuilder("su", "-c", it).redirectErrorStream(true).start() }
) {
    fun run(command: String, seconds: Long = 10, limit: Int = 8 * 1024 * 1024): CommandResult {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("目录读取已取消")
        val child = launch(supervised(command, seconds))
        val output = StringBuilder()
        val complete = java.util.concurrent.atomic.AtomicBoolean(false)
        val reader = Thread {
            runCatching {
                var overflow = false
                child.inputStream.reader(Charsets.UTF_8).use { stream ->
                    val buffer = CharArray(4096)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        synchronized(output) {
                            val retained = minOf(count, limit - output.length)
                            output.append(buffer, 0, retained)
                            overflow = overflow || retained < count
                        }
                    }
                }
                complete.set(!overflow)
            }
        }.apply { name = "tubing-directory-output"; isDaemon = true; start() }
        try {
            val finished = child.waitFor(seconds + 2, TimeUnit.SECONDS)
            if (!finished) return CommandResult(124, "目录读取超时，请重试")
            reader.join(500)
            val code = child.exitValue()
            if (code == 0 && (!complete.get() || reader.isAlive)) return CommandResult(65, "输出过大或读取不完整")
            return CommandResult(code, synchronized(output) { output.toString() })
        } finally {
            // The root-side guard keeps running until the worker exits or this pipe closes.
            // Do this before destroying su, including when Future.cancel interrupts waitFor.
            runCatching { child.outputStream.close() }
            var interrupted = false
            try { if (!child.waitFor(500, TimeUnit.MILLISECONDS)) child.destroyForcibly() }
            catch (_: InterruptedException) { interrupted = true; child.destroyForcibly() }
            runCatching { child.inputStream.close() }
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    internal fun supervised(command: String, seconds: Long): String {
        require(seconds in 1..3600)
        val quote = { value: String -> "'" + value.replace("'", "'\\''") + "'" }
        return """
            exec 3<&0
            setsid sh -c ${quote(command)} </dev/null 3<&- &
            worker=${'$'}!
            (
              IFS= read -r -t $seconds cancel <&3
              kill -KILL -- -"${'$'}worker" 2>/dev/null || :
              kill -KILL "${'$'}worker" 2>/dev/null || :
            ) &
            guard=${'$'}!
            wait "${'$'}worker"
            result=${'$'}?
            kill -KILL -- -"${'$'}worker" 2>/dev/null || :
            kill "${'$'}guard" 2>/dev/null || :
            wait "${'$'}guard" 2>/dev/null || :
            exit "${'$'}result"
        """.trimIndent()
    }
}
