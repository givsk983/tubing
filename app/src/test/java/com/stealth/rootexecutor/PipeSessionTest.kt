package com.stealth.rootexecutor

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PipeSessionTest {
    private fun exercise(answer: String, expected: String) {
        val executor = Executors.newSingleThreadExecutor()
        var child: Process? = null
        val prompt = CountDownLatch(1)
        val output = StringBuilder()
        val java = File(System.getProperty("java.home"), if (System.getProperty("os.name").orEmpty().startsWith("Windows")) "bin/java.exe" else "bin/java").absolutePath
        val classes = File(requireNotNull(PipeFixture::class.java.protectionDomain).codeSource.location.toURI()).absolutePath
        val session = PipeSession(RootExecutor(), "abc-123") {
            ProcessBuilder(java, "-Dfile.encoding=UTF-8", "-cp", classes, PipeFixture::class.java.name).redirectErrorStream(true).start().also { child = it }
        }
        try {
            val result = executor.submit<Int> {
                session.run(ManagedFile("scripts", "test.sh", "777")) { text ->
                    synchronized(output) { output.append(text); if (output.contains("PROMPT:")) prompt.countDown() }
                }
            }
            assertTrue("Must show an un-terminated prompt before receiving input", prompt.await(10, TimeUnit.SECONDS))
            session.send(answer)
            assertEquals(7, result.get(10, TimeUnit.SECONDS))
            synchronized(output) { assertTrue(output.toString(), output.contains(expected)); assertTrue(output.contains("STDERR")) }
        } finally { child?.destroyForcibly(); executor.shutdownNow() }
    }
    @Test fun promptWithoutNewlineAndUnicodeAnswerAndExitCode() = exercise("你好\r", "ANSWER:你好")
    @Test fun keyboardEofClosesChildStdin() = exercise("\u0004", "EOF")
}
