package com.stealth.rootexecutor

import org.junit.Assert.*
import org.junit.Test

class ExecutionHistoryTest {
    private fun record(id: String, name: String = "script.sh") = ExecutionRecord(id, "scripts", name, "pipe", 100L)

    @Test fun retainsRepeatedRunsAsSeparateRecordsAndBoundsHistory() {
        var records = emptyList<ExecutionRecord>()
        repeat(205) { records = ExecutionHistoryRecords.start(records, record(it.toString())) }
        assertEquals(200, records.size)
        assertEquals("5", records.first().id)
        assertEquals("204", records.last().id)
    }

    @Test fun finishingUpdatesOnlyMatchingRunAndPreservesOtherScripts() {
        val first = record("one", "first.sh")
        val second = record("two", "second.sh")
        val records = ExecutionHistoryRecords.finish(listOf(first, second), "one", "已结束 · 退出码 7", 7, 200L)
        assertEquals(7, records.first().exitCode)
        assertEquals(200L, records.first().endedAt)
        assertEquals(second, records.last())
    }

    @Test fun finishingAfterUserClearedHistoryDoesNotResurrectRecord() {
        assertTrue(ExecutionHistoryRecords.finish(emptyList(), "cleared", "已终止", null, 200L).isEmpty())
    }

    @Test fun failuresHaveEndTimeWithoutInventingExitCode() {
        val failed = ExecutionHistoryRecords.finish(listOf(record("one")), "one", "执行失败", null, 200L).single()
        assertEquals("执行失败", failed.status)
        assertEquals(200L, failed.endedAt)
        assertNull(failed.exitCode)
    }
}
