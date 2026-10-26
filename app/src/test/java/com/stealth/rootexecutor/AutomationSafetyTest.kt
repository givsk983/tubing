package com.stealth.rootexecutor

import org.junit.Assert.*
import org.junit.Test

class AutomationSafetyTest {
    @Test fun cooldownPersistsAcrossReentryAndClockRollbackFailsClosed() {
        val policy = AutomationPolicy(60, 120)
        assertNull(policy.blockedReason(100_000, 0, false))
        assertNotNull(policy.blockedReason(110_000, 100_000, true))
        assertNull(policy.blockedReason(160_000, 100_000, true))
        assertNotNull(policy.blockedReason(99_000, 100_000, false))
    }
    @Test fun oneShotRequiresExplicitRearmEvenAfterCooldown() {
        assertNotNull(AutomationPolicy(10, 120, true).blockedReason(1_000_000, 1, true))
        assertNull(AutomationPolicy(10, 120, true).blockedReason(1_000_000, 0, false))
    }
    @Test fun foregroundPreservesProfileAndRejectsNullTopAndUnknownOutput() {
        assertEquals(ForegroundIdentity(10, "com.work"), ForegroundParser.identity("topResumedActivity=ActivityRecord{x u10 com.work/.Main}"))
        assertNull(ForegroundParser.packageName("topResumedActivity=null\nmResumedActivity=ActivityRecord{x u0 com.background/.Main}"))
        assertNull(ForegroundParser.packageName("Permission denied"))
        assertEquals("com.correct", ForegroundParser.packageName("mResumedActivity=x u0 com.other/.Main\ntopResumedActivity=x u0 com.correct/.Main"))
    }
    @Test fun originalAndCloneSharePackageButKeepDistinctForegroundIdentity() {
        val original = ForegroundParser.identity("topResumedActivity=x u0 com.same/.Main")!!
        val clone = ForegroundParser.identity("topResumedActivity=x u999 com.same/.Main")!!
        assertEquals(original.packageName, clone.packageName)
        assertNotEquals(original, clone)
    }
    @Test fun promptCanSpanChunksAndAnsiSequences() {
        val buffer = PromptBuffer()
        buffer.append("\u001b[32m请选择")
        assertFalse(buffer.consume("请选择操作"))
        buffer.append("操作\u001b[0m：")
        assertTrue(buffer.consume("请选择操作"))
        assertFalse(buffer.consume("请选择操作"))
    }
    @Test fun nextPromptCannotConsumePreviouslyMatchedOutput() {
        val buffer = PromptBuffer()
        buffer.append("READY> duplicate READY>")
        assertTrue(buffer.consume("READY>"))
        assertFalse(buffer.consume("READY>"))
        buffer.append("READY>")
        assertTrue(buffer.consume("READY>"))
    }
    @Test fun promptBufferBoundsOldOutput() {
        val buffer = PromptBuffer()
        buffer.append("OLD" + "x".repeat(70_000))
        assertFalse(buffer.consume("OLD"))
        buffer.append("CURRENT>")
        assertTrue(buffer.consume("CURRENT>"))
    }
    @Test fun secretNeverLeaksAtAnyChunkBoundary() {
        val secret = "token-abc123"
        for (split in 0..secret.length) {
            val redactor = SecretRedactor(listOf(secret))
            val output = redactor.accept("before:" + secret.take(split)) +
                redactor.accept(secret.drop(split) + ":after") + redactor.finish()
            assertEquals("before:[已隐藏]:after", output)
        }
    }
    @Test fun partialSecretAtExitIsSuppressed() {
        val redactor = SecretRedactor(listOf("secret-123"))
        assertEquals("", redactor.accept("secr"))
        assertEquals("[已隐藏]", redactor.finish())
    }
    @Test fun multipleSensitiveValuesAndNonSensitiveOutput() {
        val redactor = SecretRedactor(listOf("abc123", "xyz"))
        assertEquals("[已隐藏] [已隐藏] normal", redactor.accept("abc123 xyz normal") + redactor.finish())
        assertEquals("正常输出", SecretRedactor(emptyList()).accept("正常输出"))
    }
}
