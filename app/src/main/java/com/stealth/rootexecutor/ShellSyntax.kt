package com.stealth.rootexecutor

internal enum class ShellTokenKind { COMMENT, STRING, VARIABLE, KEYWORD, NUMBER, OPERATOR }
internal data class ShellToken(val start: Int, val end: Int, val kind: ShellTokenKind)

/** Lightweight lexical highlighting, never a shell parser or execution path. Offsets are UTF-16. */
internal object ShellSyntax {
    private val keywords = setOf("if", "then", "else", "elif", "fi", "for", "while", "until", "do", "done",
        "case", "esac", "in", "function", "select", "time", "export", "local", "readonly", "return", "exit",
        "break", "continue", "unset", "eval", "exec", "source")

    fun tokens(text: String): List<ShellToken> {
        val result = ArrayList<ShellToken>()
        fun add(start: Int, end: Int, kind: ShellTokenKind) {
            if (end > start && result.size < 24_000) result.add(ShellToken(start, end, kind))
        }
        fun variable(start: Int): Int {
            var end = start + 1
            if (end >= text.length) return end
            if (text[end] == '{') {
                end++
                while (end < text.length && text[end] != '}' && text[end] != '\n') end++
                if (end < text.length && text[end] == '}') end++
            } else if (text[end].isLetter() || text[end] == '_') {
                while (end < text.length && (text[end].isLetterOrDigit() || text[end] == '_')) end++
            } else if (text[end] in "0123456789?@*#$!-" ) end++
            add(start, end, ShellTokenKind.VARIABLE)
            return end
        }
        data class HereDoc(val delimiter: String, val stripTabs: Boolean)
        val hereDocs = ArrayDeque<HereDoc>()
        var i = 0
        while (i < text.length && result.size < 24_000) {
            val start = i
            val c = text[i]
            when {
                c == '\\' -> i = minOf(i + 2, text.length)
                c == '#' && (i == 0 || text[i - 1].isWhitespace() || text[i - 1] in ";|&()") -> {
                    while (i < text.length && text[i] != '\n') i++
                    add(start, i, ShellTokenKind.COMMENT)
                }
                c == '\'' -> {
                    i++
                    while (i < text.length && text[i] != '\'') i++
                    if (i < text.length) i++
                    add(start, i, ShellTokenKind.STRING)
                }
                c == '"' -> {
                    i++
                    var segment = start
                    while (i < text.length && text[i] != '"') {
                        if (text[i] == '\\') i = minOf(i + 2, text.length)
                        else if (text[i] == '$') {
                            add(segment, i, ShellTokenKind.STRING)
                            i = variable(i); segment = i
                        } else i++
                    }
                    if (i < text.length) i++
                    add(segment, i, ShellTokenKind.STRING)
                }
                c == '$' -> i = variable(i)
                c == '<' && text.startsWith("<<<", i) -> { i += 3; add(start, i, ShellTokenKind.OPERATOR) }
                c == '<' && text.startsWith("<<", i) -> {
                    i += 2
                    val stripTabs = i < text.length && text[i] == '-'
                    if (stripTabs) i++
                    add(start, i, ShellTokenKind.OPERATOR)
                    while (i < text.length && text[i] in " \t") i++
                    val delimiterStart = i
                    val quote = text.getOrNull(i)?.takeIf { it == '\'' || it == '"' }
                    if (quote != null) i++
                    val wordStart = i
                    while (i < text.length && if (quote != null) text[i] != quote && text[i] != '\n'
                        else !text[i].isWhitespace() && text[i] !in ";|&<>()") i++
                    val delimiter = text.substring(wordStart, i)
                    if (quote != null && text.getOrNull(i) == quote) i++
                    if (delimiter.isNotEmpty()) hereDocs.addLast(HereDoc(delimiter, stripTabs))
                    add(delimiterStart, i, ShellTokenKind.STRING)
                }
                c == '\n' && hereDocs.isNotEmpty() -> {
                    i++
                    while (hereDocs.isNotEmpty()) {
                        val doc = hereDocs.removeFirst()
                        val body = i
                        while (i < text.length) {
                            val end = text.indexOf('\n', i).let { if (it < 0) text.length else it }
                            val line = text.substring(i, end).removeSuffix("\r")
                            i = minOf(end + 1, text.length)
                            if ((if (doc.stripTabs) line.trimStart('\t') else line) == doc.delimiter) break
                        }
                        add(body, i, ShellTokenKind.STRING)
                    }
                }
                c in ";|&<>(){}" -> { i++; add(start, i, ShellTokenKind.OPERATOR) }
                c.isLetterOrDigit() || c == '_' -> {
                    while (i < text.length && (text[i].isLetterOrDigit() || text[i] in "_./-#")) i++
                    val word = text.substring(start, i)
                    when {
                        word in keywords -> add(start, i, ShellTokenKind.KEYWORD)
                        word.all { it.isDigit() } -> add(start, i, ShellTokenKind.NUMBER)
                    }
                }
                else -> i++
            }
        }
        return result
    }
}
