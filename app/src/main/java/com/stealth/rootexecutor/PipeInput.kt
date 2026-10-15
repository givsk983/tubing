package com.stealth.rootexecutor

/** Pipes have no terminal line discipline: keep editing local until Enter is pressed. */
class PipeInput(
    private val write: (String) -> Unit,
    private val echo: (String) -> Unit,
    private val interrupt: () -> Unit,
    private val eof: () -> Unit
) {
    private val pending = StringBuilder()
    private var previousCr = false
    private var escape = 0
    private var ended = false

    @Synchronized fun accept(text: String) {
        if (ended) return
        var offset = 0
        while (offset < text.length) {
            val code = text.codePointAt(offset)
            offset += Character.charCount(code)
            // Direction/function keys do not form useful line input in pipe mode.
            if (escape != 0) {
                if (escape == 1 && (code == 91 || code == 79)) { escape = 2; continue }
                if (escape == 2) { if (code in 64..126) escape = 0; continue }
                escape = 0
            }
            if (code == 27) { escape = 1; continue }
            if (code == 10 && previousCr) { previousCr = false; continue }
            previousCr = code == 13
            when (code) {
                10, 13 -> { write(pending.toString() + "\n"); pending.clear(); echo("\r\n") }
                8, 127 -> if (pending.isNotEmpty()) {
                    val start = pending.offsetByCodePoints(pending.length, -1)
                    pending.delete(start, pending.length); echo("\b \b")
                }
                3 -> { pending.clear(); interrupt(); echo("^C\r\n") }
                4 -> if (pending.isNotEmpty()) { write(pending.toString()); pending.clear() } else { ended = true; eof(); echo("\r\n[标准输入已关闭]\r\n"); return }
                21 -> { while (pending.isNotEmpty()) { pending.delete(pending.offsetByCodePoints(pending.length, -1), pending.length); echo("\b \b") } }
                9 -> { pending.append('\t'); echo("\t") }
                else -> if (code >= 32) { val value = String(Character.toChars(code)); pending.append(value); echo(value) }
            }
        }
    }
}
