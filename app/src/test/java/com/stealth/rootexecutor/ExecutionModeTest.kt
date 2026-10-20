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

    @Test fun bothBackendsRepairAndVerifyPermissionsForScriptsAndBinariesRegardlessOfListedMode() {
        for (kind in listOf("scripts", "binaries")) {
            for (slave in listOf(null, "/dev/pts/12")) {
                val command = RootExecutor().sessionCommand(ManagedFile(kind, "test", "600"), slave, "abc-123")
                val chmod = command.indexOf("chmod 777")
                val verify = command.indexOf("stat -c %a")
                val launch = command.indexOf("exec setsid -w")
                assertTrue(chmod >= 0 && verify > chmod && launch > verify)
                assertTrue(command.contains("set -eu"))
                assertTrue(command.contains("权限验证失败'; exit 38"))
            }
        }
    }
}
