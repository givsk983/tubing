package com.stealth.rootexecutor

import org.junit.Assert.assertThrows
import org.junit.Test

class UpdateApkTest {
    private val installed = UpdateIdentity("com.stealth.rootexecutor", "1.5.0", 25, setOf("trusted"))
    private val incoming = installed.copy(version = "1.5.1", code = 26)

    @Test fun acceptsMatchingNewerRelease() {
        UpdateApk.checkIdentity(installed, incoming, "v1.5.1")
    }

    @Test fun rejectsOtherPackagesSignersOldCodesAndMismatchedTags() {
        listOf(incoming.copy(packageName = "org.other.app"), incoming.copy(signers = setOf("foreign")),
            incoming.copy(signers = emptySet()), incoming.copy(code = 25), incoming.copy(code = 24),
            incoming.copy(version = "1.5.2"), incoming.copy(version = null)).forEach {
            assertThrows(IllegalStateException::class.java) { UpdateApk.checkIdentity(installed, it, "v1.5.1") }
        }
        assertThrows(IllegalStateException::class.java) { UpdateApk.checkIdentity(installed, incoming.copy(version = "1.5.0"), "v1.5.0") }
        assertThrows(IllegalStateException::class.java) { UpdateApk.checkIdentity(installed.copy(version = "unknown"), incoming, "v1.5.1") }
        assertThrows(IllegalStateException::class.java) { UpdateApk.checkIdentity(installed, incoming, "latest") }
    }
}
