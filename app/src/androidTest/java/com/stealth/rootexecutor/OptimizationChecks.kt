package com.stealth.rootexecutor

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import android.webkit.WebView
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal object OptimizationChecks {
    fun run(context: Context, onUi: (() -> Unit) -> Unit, launch: () -> Activity): String {
        check(!SessionState.running && !SessionState.startGate.isPreparing()) { "Do not interrupt a user session" }
        val root = RootExecutor()
        check(root.initialize().code == 0)
        val fixture = File(context.cacheDir, "optimizations-${UUID.randomUUID()}").apply { mkdirs() }
        val executor = Executors.newSingleThreadExecutor()
        val report = mutableListOf<String>()
        try {
            val pid = File(fixture, "pid")
            val descendant = File(fixture, "descendant")
            val completed = CountDownLatch(1)
            val task = executor.submit {
                try {
                    CancellableCommand().run("""
                        echo ${'$'}${'$'} > ${root.quote(pid.path)}
                        sleep 30 &
                        echo ${'$'}! > ${root.quote(descendant.path)}
                        wait
                    """.trimIndent(), seconds = 30)
                } finally { completed.countDown() }
            }
            val deadline = SystemClock.elapsedRealtime() + 5000
            while ((!pid.isFile || !descendant.isFile || descendant.length() == 0L) && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
            check(pid.isFile && descendant.isFile) { "Root query did not start" }
            val ids = listOf(pid.readText().trim(), descendant.readText().trim())
            check(ids.all { it.matches(Regex("[0-9]+")) })
            val cancelStart = SystemClock.elapsedRealtime()
            task.cancel(true)
            check(completed.await(3, TimeUnit.SECONDS)) { "Cancelled root query still occupies its thread" }
            val elapsed = SystemClock.elapsedRealtime() - cancelStart
            for (id in ids) {
                val stat = root.runBounded("cat /proc/$id/stat 2>/dev/null || :", 2).output
                check(stat.isBlank() || stat.substringAfterLast(')').trim().firstOrNull() == 'Z') { "Cancelled query left process $id alive: $stat" }
            }
            report.add("Root query cancellation: shell and sleep descendant stopped in ${elapsed}ms")
            File(fixture, "中文 ' file.txt").writeText("fixture")
            val listed = root.browse(fixture.path)
            check(listed.first.code == 0 && listed.second.any { it.name == "中文 ' file.txt" }) { "Normal scan failed: ${listed.first}" }
            val timed = CancellableCommand().run("sleep 30", seconds = 1)
            check(timed.code in setOf(124, 137)) { "Unexpected timeout result: $timed" }
            report.add("Supervised directory scan: quoted Unicode filename, complete output and timeout passed")

            val activity = launch()
            try {
                fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
                var ready = false
                repeat(100) {
                    if (!ready) {
                        onUi { ready = field("granted").getBoolean(activity) }
                        if (!ready) SystemClock.sleep(100)
                    }
                }
                check(ready) { "Root screen not ready" }
                onUi {
                    field("tab").setInt(activity, 0)
                    // Build the full execution view directly without changing the user's home preference.
                    MainActivity::class.java.getDeclaredMethod("executionPage").apply { isAccessible = true }.invoke(activity)
                }
                ready = false
                repeat(100) {
                    if (!ready) {
                        onUi { ready = field("terminalReady").getBoolean(activity) }
                        if (!ready) SystemClock.sleep(100)
                    }
                }
                check(ready) { "Terminal bridge not ready" }
                // Hold the UI briefly while several MiB arrive, then verify native and xterm queues recover.
                onUi {
                    SessionState.clearOutput()
                    (field("terminalOutput").get(activity) as TerminalOutputPump).reset()
                    val web = field("terminal").get(activity) as WebView
                    web.evaluateJavascript("window.resetOutput()", null)
                    repeat(4000) { SessionState.output("\u001b[31m" + "x".repeat(1024) + "\u001b[0m\r\n") }
                    SessionState.output("\u001b]0;" + "hidden".repeat(100_000))
                    SessionState.output("title-end\u0007\u001b[0m\r\nOPTIMIZATION_FINAL_你好😀\r\n")
                }
                fun js(expression: String): String {
                    val latch = CountDownLatch(1)
                    var result = ""
                    onUi { (field("terminal").get(activity) as WebView).evaluateJavascript(expression) { result = it; latch.countDown() } }
                    check(latch.await(5, TimeUnit.SECONDS)) { "WebView JS callback timed out" }
                    return result
                }
                val expression = "(()=>{const b=term.buffer.active;let s='';for(let i=Math.max(0,b.length-100);i<b.length;i++)s+=b.getLine(i).translateToString(true)+'\\n';return s})()"
                var screen = ""
                val renderDeadline = SystemClock.elapsedRealtime() + 10000
                while (!screen.contains("OPTIMIZATION_FINAL_") && SystemClock.elapsedRealtime() < renderDeadline) {
                    SystemClock.sleep(100); screen = js(expression)
                }
                check(screen.contains("OPTIMIZATION_FINAL_") && screen.contains("你好")) { "Terminal did not recover: $screen" }
                check(!screen.contains("title-end")) { "Discarded OSC leaked into visible text: $screen" }
                check(js("term.buffer.active.getLine(term.buffer.active.baseY+term.buffer.active.cursorY-1).getCell(0).isFgDefault()") == "true") { "SGR did not recover" }
                report.add("Real xterm/WebView: 4MiB burst, oversized split OSC, acknowledgement flow and Unicode tail recovered")
                onUi {
                    val pump = field("terminalOutput").get(activity) as TerminalOutputPump
                    pump.offer("OLD_SESSION_MARKER\r\n".repeat(500))
                    pump.reset()
                    (field("terminal").get(activity) as WebView).evaluateJavascript("window.resetOutput()", null)
                    pump.offer("NEW_SESSION_MARKER\r\n")
                }
                SystemClock.sleep(300)
                val reset = js(expression)
                check(reset.contains("NEW_SESSION_MARKER") && !reset.contains("OLD_SESSION_MARKER")) { "Old queued output crossed the session reset: $reset" }
                report.add("Queued xterm output cannot cross a new-session reset")
            } finally { onUi { activity.finish() } }
        } finally {
            executor.shutdownNow()
            // Only remove the unique fixture created above; never touch imported user programs.
            root.deletePath(fixture.path, true)
        }
        return report.joinToString("\n")
    }
}
