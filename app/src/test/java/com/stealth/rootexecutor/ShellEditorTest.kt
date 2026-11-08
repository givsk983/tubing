package com.stealth.rootexecutor

import org.junit.Assert.*
import org.junit.Test

class ShellEditorTest {
    private fun decode(value: String) = ShellScriptDocument.decode("/test.sh", value.toByteArray(Charsets.UTF_8), "fingerprint")

    @Test fun utf8BomAndCrlfSurviveEditingWithoutAddingFinalNewline() {
        val original = "\uFEFF#!/system/bin/sh\r\necho 中文"
        val doc = decode(original)
        assertEquals("#!/system/bin/sh\necho 中文", doc.text)
        assertTrue(doc.bom)
        assertTrue(doc.crlf)
        assertArrayEquals(original.replace("中文", "修改后").toByteArray(Charsets.UTF_8), doc.encode(doc.text.replace("中文", "修改后")))
        assertArrayEquals(original.toByteArray(Charsets.UTF_8), doc.encode(doc.text.replace("\n", "\r\n")))
    }

    @Test fun emptyAndMixedLineEndingFilesRoundTripExactly() {
        listOf("", "echo hello", "echo hello\n", "a\r\nb\nc\r\n").forEach {
            val doc = decode(it)
            assertArrayEquals(it.toByteArray(Charsets.UTF_8), doc.encode(doc.text))
        }
    }

    @Test fun rejectsBinaryAndInvalidUtf8RatherThanReplacingBytes() {
        listOf(byteArrayOf(0x7f, 0x45, 0x4c, 0x46, 0), byteArrayOf(0xc3.toByte(), 0x28), byteArrayOf(27)).forEach {
            assertTrue(runCatching { ShellScriptDocument.decode("/bad.sh", it, "") }.isFailure)
        }
        assertTrue(runCatching { decode("echo ok").encode("echo\u0000bad") }.isFailure)
        assertTrue(runCatching { decode("").encode("\uD83D") }.isFailure)
    }

    @Test fun byteLimitCountsUtf8BytesAndAllowsExactBoundary() {
        val exact = "a".repeat(ShellScriptDocument.MAX_BYTES)
        assertEquals(ShellScriptDocument.MAX_BYTES, decode(exact).encode(exact).size)
        assertTrue(runCatching { decode(exact + "b") }.isFailure)
        assertTrue(runCatching { decode("").encode("中".repeat(ShellScriptDocument.MAX_BYTES / 3 + 1)) }.isFailure)
    }

    @Test fun sha256UsesAllBytesAndLeadingZeros() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", ShellScriptDocument.sha256(byteArrayOf()))
    }

    private fun highlighted(text: String, kind: ShellTokenKind) = ShellSyntax.tokens(text)
        .filter { it.kind == kind }.map { text.substring(it.start, it.end) }

    @Test fun highlightsKeywordsVariablesStringsAndRealComments() {
        val script = "#!/system/bin/sh\nif [ \"${'$'}HOME\" ]; then\n echo '中文 # literal' foo#bar # comment\nfi"
        assertEquals(listOf("if", "then", "fi"), highlighted(script, ShellTokenKind.KEYWORD))
        assertEquals(listOf("${'$'}HOME"), highlighted(script, ShellTokenKind.VARIABLE))
        assertEquals(listOf("#!/system/bin/sh", "# comment"), highlighted(script, ShellTokenKind.COMMENT))
        assertTrue(highlighted(script, ShellTokenKind.STRING).contains("'中文 # literal'"))
    }

    @Test fun respectsEscapesAndDoesNotExpandSingleQuotedVariables() {
        val script = "echo '${'$'}HOME' \"\\${'$'}HOME ${'$'}{name:-x} ${'$'}?\""
        assertEquals(listOf("${'$'}{name:-x}", "${'$'}?"), highlighted(script, ShellTokenKind.VARIABLE))
        assertEquals(emptyList<String>(), highlighted("echo \\#text", ShellTokenKind.COMMENT))
    }

    @Test fun heredocBodyIsNotInterpretedAsCommentsOrShellKeywords() {
        val script = "cat <<-'EOF'\n\t# literal if ${'$'}HOME\n\tEOF\nif true; then exit 0; fi"
        assertEquals(emptyList<String>(), highlighted(script, ShellTokenKind.COMMENT))
        assertEquals(listOf("if", "then", "exit", "fi"), highlighted(script, ShellTokenKind.KEYWORD))
        assertEquals(emptyList<String>(), highlighted(script, ShellTokenKind.VARIABLE))
        assertTrue(highlighted(script, ShellTokenKind.STRING).any { "# literal" in it })
    }

    @Test fun hereStringDoesNotSwallowFollowingCommands() {
        assertEquals(listOf("if", "then", "fi"), highlighted("cat <<< word\nif true; then :; fi", ShellTokenKind.KEYWORD))
    }

    @Test fun unicodeAndUnfinishedQuotesKeepValidNonOverlappingOffsets() {
        listOf("echo 😀 \"你好 ${'$'}name", "echo 'unfinished\nnext", "${'$'}{unfinished", "", "x".repeat(256 * 1024)).forEach { source ->
            val tokens = ShellSyntax.tokens(source)
            assertTrue(tokens.all { it.start >= 0 && it.end <= source.length && it.start < it.end })
            assertTrue(tokens.zipWithNext().all { (a, b) -> a.end <= b.start })
        }
    }
}
