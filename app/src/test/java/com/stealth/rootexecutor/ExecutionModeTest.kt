package com.stealth.rootexecutor

import org.junit.Assert.*
import org.junit.Test

class ExecutionModeTest {
    @Test fun missingOrUnknownPreferenceUsesPipe() {
        assertEquals(ExecutionMode.PIPE, ExecutionMode.fromKey(null))
        assertEquals(ExecutionMode.PIPE, ExecutionMode.fromKey("invalid"))
        assertEquals(ExecutionMode.PTY, ExecutionMode.fromKey("pty"))
    }
    @Test fun pipeHasNoPtyRedirectionButKeepsSafetyChecks() {
        val command = RootExecutor().sessionCommand(ManagedFile("scripts", "中文 test.sh", "777"), null, "abc-123")
        assertFalse(command.contains("/dev/pts/"))
        assertTrue(command.contains("export TERM=dumb"))
        assertTrue(command.contains("chmod 777"))
        assertTrue(command.contains("stat -c %a"))
        assertTrue(command.contains("noexec"))
        assertTrue(command.contains("setsid -w"))
        assertTrue(command.contains("test.sh"))
    }
    @Test fun ptyKeepsSlaveAndTerminalEnvironment() {
        val command = RootExecutor().sessionCommand(ManagedFile("binaries", "test", "777"), "/dev/pts/12", "abc-123")
        assertTrue(command.contains("/dev/pts/12"))
        assertTrue(command.contains("xterm-256color"))
    }
    @Test(expected = IllegalArgumentException::class) fun traversalIsRejected() {
        RootExecutor().sessionCommand(ManagedFile("scripts", "../test.sh", "777"), null, "abc-123")
    }
    @Test(expected = IllegalArgumentException::class) fun maliciousSlaveIsRejected() {
        RootExecutor().sessionCommand(ManagedFile("scripts", "test.sh", "777"), "/dev/pts/1;id", "abc-123")
    }
}
