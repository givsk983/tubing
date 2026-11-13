package com.stealth.rootexecutor

/** Bounded display text. Eviction only resumes outside ANSI strings and between Unicode characters. */
internal class TerminalOutputBuffer(private val retain: Int = 400_000, private val maximum: Int = 512_000) {
    private val text = StringBuilder()
    private var startState = 0
    private var endState = 0
    private var discarding = false
    private var truncated = false

    init { require(retain > 1 && maximum >= retain) }

    fun append(value: String) {
        var offset = 0
        if (discarding) {
            while (offset < value.length && endState != 0) endState = advance(endState, value[offset++])
            if (endState != 0) return
            discarding = false
            startState = 0
        }
        text.append(value, offset, value.length)
        for (index in offset until value.length) endState = advance(endState, value[index])
        if (text.length <= maximum) return
        val desired = text.length - retain
        var state = startState
        var cut = -1
        for (index in text.indices) {
            state = advance(state, text[index])
            if (index + 1 >= desired && state == 0 && !text[index].isHighSurrogate()) {
                cut = index + 1
                break
            }
        }
        if (cut >= 0) {
            text.delete(0, cut)
            startState = 0
        } else if (endState == 0 && text.last().isHighSurrogate()) {
            // A UTF-16 pair can straddle two producer callbacks.
            text.delete(0, text.length - 1)
            startState = 0
        } else {
            text.clear()
            startState = endState
            discarding = endState != 0
        }
        truncated = true
    }

    fun snapshot(): String = (if (truncated) OMITTED else "") + text

    fun take(maxChars: Int = Int.MAX_VALUE): String {
        require(maxChars > OMITTED.length)
        val prefix = if (truncated) OMITTED else ""
        truncated = false
        var count = minOf(text.length, maxChars - prefix.length)
        if (count > 0 && text[count - 1].isHighSurrogate()) count--
        val result = text.substring(0, count)
        for (char in result) startState = advance(startState, char)
        text.delete(0, count)
        return prefix + result
    }

    fun clear(continueStream: Boolean = false) {
        text.clear()
        if (!continueStream) endState = 0
        startState = endState
        discarding = endState != 0
        truncated = false
    }

    companion object {
        // CAN cancels any partial control sequence already delivered to xterm; SGR clears old colors.
        const val OMITTED = "\u0018\u001b[0m\r\n[Tubing] 输出较多，较早的显示内容已省略。\r\n"

        private fun advance(state: Int, char: Char): Int {
            if (char == '\u0018' || char == '\u001a') return 0
            return when (state) {
                0 -> when (char) { '\u001b' -> 1; '\u009b' -> 2; '\u009d' -> 3; '\u0090', '\u0098', '\u009e', '\u009f' -> 4; else -> 0 }
                1 -> when (char) { '[' -> 2; ']' -> 3; 'P', 'X', '^', '_' -> 4; '\u001b' -> 1; in '\u0020'..'\u002f' -> 7; else -> 0 }
                2 -> when (char) { '\u001b' -> 1; in '@'..'~' -> 0; else -> 2 }
                3 -> when (char) { '\u0007', '\u009c' -> 0; '\u001b' -> 5; else -> 3 }
                4 -> when (char) { '\u009c' -> 0; '\u001b' -> 6; else -> 4 }
                5 -> when (char) { '\\', '\u0007', '\u009c' -> 0; '\u001b' -> 5; else -> 3 }
                6 -> when (char) { '\\', '\u009c' -> 0; '\u001b' -> 6; else -> 4 }
                else -> when (char) { '\u001b' -> 1; in '\u0030'..'\u007e' -> 0; else -> 7 }
            }
        }
    }
}

/** One xterm write in flight. Acknowledgements prevent an unbounded WebView/JS write queue. */
internal class TerminalOutputPump(private val send: (Long, String) -> Unit) {
    private val pending = TerminalOutputBuffer()
    private var nextId = 0L
    private var inFlight: Long? = null

    fun offer(text: String) { pending.append(text); flush() }
    fun consumed(id: Long) {
        if (id != inFlight) return
        inFlight = null
        flush()
    }
    fun reset() { pending.clear(); inFlight = null; nextId++ }

    private fun flush() {
        if (inFlight != null) return
        val batch = pending.take(32_768)
        if (batch.isEmpty()) return
        val id = ++nextId
        inFlight = id
        send(id, batch)
    }
}
