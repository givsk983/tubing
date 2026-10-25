package com.stealth.rootexecutor

import org.junit.Assert.*
import org.junit.Test

class AppUpdatesTest {
    private fun release(tag: String, draft: Boolean = false, prerelease: Boolean = false) =
        AppRelease(tag, tag, "", "${AppUpdates.RELEASES_URL}/tag/$tag", null, draft, prerelease)

    @Test fun numericVersionsDoNotUseLexicographicOrdering() {
        assertTrue(AppUpdates.isNewer(release("v1.10.0"), "1.9.9"))
        assertTrue(AppUpdates.isNewer(release("v2.0.0"), "1.99.99"))
        assertFalse(AppUpdates.isNewer(release("v1.4.1"), "1.4.1"))
        assertFalse(AppUpdates.isNewer(release("v1.4.0"), "1.4.1"))
    }

    @Test fun prereleasesAreCheckedBecauseTheRepositoryPublishesTestBuilds() {
        val newest = release("v1.4.2", prerelease = true)
        assertEquals(newest, AppUpdates.latest(listOf(release("v1.4.0"), newest)))
        assertTrue(AppUpdates.isNewer(newest, "1.4.1"))
    }

    @Test fun semanticPrereleasePrecedenceAndBuildMetadataAreHandled() {
        val tags = listOf("1.4.1-alpha", "1.4.1-alpha.1", "1.4.1-alpha.2", "1.4.1-alpha.10", "1.4.1-beta", "1.4.1-rc.1", "1.4.1")
        tags.zipWithNext().forEach { (older, newer) -> assertTrue(AppUpdates.isNewer(release(newer), older)) }
        assertFalse(AppUpdates.isNewer(release("v1.4.1+build.2"), "1.4.1+build.1"))
    }

    @Test fun draftsInvalidTagsAndUntrustedPagesAreIgnored() {
        val current = release("v1.4.1")
        assertEquals(current, AppUpdates.latest(listOf(
            release("v9.0.0", draft = true), release("latest"), current,
            release("v8.0.0").copy(pageUrl = "https://example.com/releases/v8.0.0")
        )))
        assertNull(AppUpdates.latest(emptyList()))
        listOf("", "v1.4", "1.4.1.0", "1.04.1", "1.4.1-01", "9999999999999.0.0").forEach { assertNull(AppVersion.parse(it)) }
    }

    @Test fun downloadLinksMustBelongToTheReleaseRepository() {
        assertTrue(AppUpdates.trustedUrl("https://github.com/givsk983/tubing/releases/download/v1.4.1/tubing-1.4.1.apk", "/releases/download/"))
        listOf("http://github.com/givsk983/tubing/releases/download/a", "https://github.com.evil.test/givsk983/tubing/releases/download/a",
            "https://github.com/other/tubing/releases/download/a", "https://github.com/givsk983/tubing/releases/download/").forEach {
            assertFalse(AppUpdates.trustedUrl(it, "/releases/download/"))
        }
    }
}
