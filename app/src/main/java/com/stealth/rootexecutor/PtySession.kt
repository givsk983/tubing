package com.stealth.rootexecutor

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

/** Bionic allocates the PTY; the separate su process opens its slave after setsid. */
class PtySession(private val root: RootExecutor, private val token: String) : ExecutionSession {
    interface LibC : Library {
        fun open(path: String, flags: Int): Int
        fun grantpt(fd: Int): Int
        fun unlockpt(fd: Int): Int
        fun ptsname(fd: Int): Pointer?
        fun read(fd: Int, buffer: ByteArray, size: NativeLong): NativeLong
        fun write(fd: Int, buffer: ByteArray, size: NativeLong): NativeLong
        fun ioctl(fd: Int, request: NativeLong, value: Pointer): Int
        fun close(fd: Int): Int
    }
    private val libc = Native.load("c", LibC::class.java)
    private var fd = -1
    @Volatile private var closed = false
    @Volatile private var process: Process? = null
    private val stopOnce = AtomicBoolean(false)
    override val inputReady: Boolean get() = fd >= 0 && process?.isAlive == true

    override fun run(file: ManagedFile, output: (String) -> Unit): Int {
        check(!closed) { "执行已取消" }
        fd = libc.open("/dev/ptmx", 2 or 0x100 or 0x800 or 0x80000)
        check(fd >= 0) { "无法创建 PTY（错误 ${Native.getLastError()}），请检查 SELinux 限制" }
        try {
            check(libc.grantpt(fd) == 0 && libc.unlockpt(fd) == 0) { "无法解锁 PTY" }
            val slave = libc.ptsname(fd)?.getString(0) ?: error("无法读取 PTY 路径")
            resize(SessionState.cols, SessionState.rows)
            check(!closed) { "执行已取消" }
            val child = ProcessBuilder("su", "-c", root.sessionCommand(file, slave, token)).redirectErrorStream(true).start()
            process = child
            val diagnostics = Thread {
                child.inputStream.reader().use { reader ->
                    val chars = CharArray(2048)
                    while (true) { val n = reader.read(chars); if (n < 0) break; output(String(chars, 0, n)) }
                }
            }.apply { isDaemon = true; start() }
            val source = object : InputStream() {
                override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 255 }
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val buffer = ByteArray(minOf(len, 8192))
                    var afterExit = 0
                    while (!closed) {
                        val n = libc.read(fd, buffer, NativeLong(buffer.size.toLong())).toInt()
                        if (n > 0) { buffer.copyInto(b, off, 0, n); return n }
                        if (!child.isAlive && ++afterExit >= 10) return -1
                        val errno = Native.getLastError()
                        if (n < 0 && errno !in setOf(4, 5, 11)) error("PTY 读取失败（错误 $errno）")
                        Thread.sleep(20)
                    }
                    return -1
                }
            }
            InputStreamReader(source, Charsets.UTF_8).use { reader ->
                val chars = CharArray(4096)
                while (true) { val n = reader.read(chars); if (n < 0) break; output(String(chars, 0, n)) }
            }
            val result = child.waitFor()
            diagnostics.join(2000)
            return result
        } finally { closeMaster() }
    }

    @Synchronized override fun send(text: String) {
        if (fd < 0 || closed) return
        val bytes = text.toByteArray(Charsets.UTF_8)
        var offset = 0
        while (offset < bytes.size && !closed) {
            val part = bytes.copyOfRange(offset, minOf(offset + 4096, bytes.size))
            val n = libc.write(fd, part, NativeLong(part.size.toLong())).toInt()
            if (n > 0) offset += n else if (Native.getLastError() == 11) Thread.sleep(10) else return
        }
    }

    @Synchronized override fun resize(cols: Int, rows: Int) {
        if (fd < 0 || closed) return
        val size = Memory(8).apply { clear(); setShort(0, rows.coerceIn(2, 500).toShort()); setShort(2, cols.coerceIn(10, 500).toShort()) }
        libc.ioctl(fd, NativeLong(0x5414), size)
    }

    override fun stop() {
        if (!stopOnce.compareAndSet(false, true)) return
        closed = true
        root.stopSession(token)
        process?.destroy()
        closeMaster()
    }

    @Synchronized private fun closeMaster() {
        closed = true
        if (fd >= 0) { libc.close(fd); fd = -1 }
    }
}
