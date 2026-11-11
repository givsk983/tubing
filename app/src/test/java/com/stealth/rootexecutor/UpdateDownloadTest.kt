package com.stealth.rootexecutor

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class UpdateDownloadTest {
    @get:Rule val temporary = TemporaryFolder()
    private val content = ByteArray(70_000) { (it % 256).toByte() }
    private val url = "https://github.com/${AppUpdates.REPOSITORY}/releases/download/v1.5.2/tubing-v1.5.2.apk"
    private fun release(size: Long = content.size.toLong(), digest: String? = null) =
        AppRelease("v1.5.2", "", "", "", url, apkSize = size, apkSha256 = digest)
    private class Response(private val bytes: ByteArray, private val status: Int = 200,
        private val location: String? = null, private val length: Long = bytes.size.toLong(),
        private val stream: InputStream? = null) : HttpURLConnection(URL("https://github.com")) {
        var disconnected = false
        override fun connect() {}
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getContentLengthLong() = length
        override fun getHeaderField(name: String?) = if (name == "Location") location else null
        override fun getInputStream() = stream ?: ByteArrayInputStream(bytes)
    }
    private fun hash() = MessageDigest.getInstance("SHA-256").digest(content)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    @Test fun followsGitHubAssetRedirectAndPublishesOnlyCompleteVerifiedApk() {
        val directory = temporary.newFolder()
        val redirect = Response(byteArrayOf(), 302, "https://release-assets.githubusercontent.com/github-production-release-asset/test?token=abc")
        val response = Response(content)
        val queue = ArrayDeque(listOf(redirect, response))
        val updates = mutableListOf<Pair<Long, Long>>()
        val file = UpdateDownload { queue.removeFirst() }.download(release(digest = hash()), directory) { done, total -> updates += done to total }
        assertArrayEquals(content, file.readBytes())
        assertEquals(listOf(file.name), directory.list()!!.toList())
        assertEquals(content.size.toLong() to content.size.toLong(), updates.last())
        assertTrue(redirect.disconnected && response.disconnected)
    }

    @Test fun permitsStreamingWithoutLengthOrLegacyDigest() {
        val response = Response(content, length = -1)
        val file = UpdateDownload { response }.download(release(size = 0), temporary.newFolder()) { _, _ -> }
        assertArrayEquals(content, file.readBytes())
    }

    @Test fun truncationDigestMismatchEmptyAndOversizeResponsesLeaveNoApkOrPartialFile() {
        val cases = listOf(
            release() to Response(content.copyOf(50), length = content.size.toLong()),
            release(digest = "0".repeat(64)) to Response(content),
            release(size = 0) to Response(byteArrayOf()),
            release(size = 0) to Response(content, length = UpdateDownload.MAX_BYTES + 1),
            release() to Response(content, length = content.size + 1L),
            release() to Response(content, status = 404)
        )
        cases.forEach { (release, response) ->
            val directory = temporary.newFolder()
            assertThrows(IllegalStateException::class.java) { UpdateDownload { response }.download(release, directory) { _, _ -> } }
            assertTrue(directory.list()!!.isEmpty())
            assertTrue(response.disconnected)
        }
    }

    @Test fun cancelAndReadFailureCleanUpPartialDownloads() {
        val directory = temporary.newFolder()
        val response = Response(content)
        val task = UpdateDownload { response }
        assertThrows(IOException::class.java) {
            task.download(release(), directory) { received, _ -> if (received > 0) task.cancel() }
        }
        assertTrue(directory.list()!!.isEmpty())
        assertTrue(response.disconnected)
        val broken = Response(content, stream = object : InputStream() { override fun read(): Int = throw IOException("broken") })
        assertThrows(IOException::class.java) { UpdateDownload { broken }.download(release(), directory) { _, _ -> } }
        assertTrue(directory.list()!!.isEmpty())
        assertTrue(broken.disconnected)
    }

    @Test fun redirectValidationRejectsHttpForeignHostsCredentialsAndRedirectLoops() {
        listOf("http://release-assets.githubusercontent.com/file", "https://evil.test/file", "https://github.com.evil.test/file",
            "https://user@release-assets.githubusercontent.com/file", "https://release-assets.githubusercontent.com:444/file",
            "https://github.com/other/repo/releases/download/file").forEach { target ->
            val directory = temporary.newFolder()
            assertThrows(IllegalArgumentException::class.java) {
                UpdateDownload { Response(byteArrayOf(), 302, target) }.download(release(), directory) { _, _ -> }
            }
            assertTrue(directory.list()!!.isEmpty())
        }
        val directory = temporary.newFolder()
        assertThrows(IllegalStateException::class.java) {
            UpdateDownload { Response(byteArrayOf(), 302, url) }.download(release(), directory) { _, _ -> }
        }
        assertTrue(directory.list()!!.isEmpty())
    }
}
