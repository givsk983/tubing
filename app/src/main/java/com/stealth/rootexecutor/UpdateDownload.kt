package com.stealth.rootexecutor

import java.io.File
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

/** One cancellable download. Only a fully received and digest-checked file is published. */
internal class UpdateDownload(
    private val connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }
) {
    @Volatile private var cancelled = false
    @Volatile private var connection: HttpURLConnection? = null

    fun cancel() { cancelled = true; connection?.disconnect() }

    private fun ensureActive() {
        if (cancelled || Thread.currentThread().isInterrupted) throw InterruptedIOException("下载已取消")
    }

    fun download(release: AppRelease, directory: File, progress: (Long, Long) -> Unit): File {
        val source = release.apkUrl ?: error("此版本尚未上传 APK，请稍后重试。")
        require(AppUpdates.trustedUrl(source, "/releases/download/")) { "更新下载地址无效" }
        require(release.apkSize in 0..MAX_BYTES) { "更新安装包过大" }
        require(release.apkSha256 == null || Regex("[0-9a-fA-F]{64}").matches(release.apkSha256)) { "更新摘要无效" }
        check(directory.isDirectory || directory.mkdirs()) { "无法创建更新缓存" }
        val temporary = File.createTempFile("update-", ".part", directory)
        val apk = File(directory, temporary.nameWithoutExtension + ".apk")
        try {
            var url = URL(source)
            repeat(6) { redirects ->
                ensureActive()
                require(allowedDownloadUrl(url.toString())) { "更新重定向地址不可信" }
                val current = connect(url)
                connection = current
                try {
                    ensureActive()
                    current.instanceFollowRedirects = false
                    current.connectTimeout = 10_000
                    current.readTimeout = 15_000
                    current.useCaches = false
                    current.setRequestProperty("Accept-Encoding", "identity")
                    current.setRequestProperty("User-Agent", "Tubing-App-Updater")
                    val status = current.responseCode
                    if (status in listOf(301, 302, 303, 307, 308)) {
                        check(redirects < 5) { "更新下载重定向次数过多" }
                        url = URL(url, current.getHeaderField("Location") ?: error("更新下载重定向无效"))
                    } else {
                        check(status == 200) { "更新下载失败（HTTP $status），请重试。" }
                        val length = current.contentLengthLong
                        check(length <= MAX_BYTES) { "更新安装包过大" }
                        check(release.apkSize == 0L || length < 0 || length == release.apkSize) { "更新安装包大小与发布信息不符" }
                        val total = release.apkSize.takeIf { it > 0 } ?: length
                        val digest = MessageDigest.getInstance("SHA-256")
                        var received = 0L
                        progress(0, total)
                        current.inputStream.use { input ->
                            temporary.outputStream().buffered().use { output ->
                                val buffer = ByteArray(32 * 1024)
                                while (true) {
                                    ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    received += count
                                    check(received <= MAX_BYTES && (total <= 0 || received <= total)) { "更新安装包大小异常" }
                                    output.write(buffer, 0, count)
                                    digest.update(buffer, 0, count)
                                    progress(received, total)
                                }
                            }
                        }
                        ensureActive()
                        check(received > 0 && (total <= 0 || received == total)) { "更新安装包下载不完整，请重试。" }
                        val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                        check(release.apkSha256 == null || hash.equals(release.apkSha256, ignoreCase = true)) { "更新安装包摘要校验失败，请重新下载。" }
                        check(temporary.renameTo(apk)) { "无法保存更新安装包" }
                        return apk
                    }
                } finally { current.disconnect(); connection = null }
            }
            error("更新下载重定向失败")
        } catch (error: Exception) {
            apk.delete()
            throw error
        } finally { temporary.delete() }
    }

    companion object {
        const val MAX_BYTES = 128L * 1024 * 1024

        fun allowedDownloadUrl(url: String): Boolean = runCatching {
            val uri = URI(url)
            uri.scheme == "https" && uri.userInfo == null && uri.port == -1 && uri.fragment == null &&
                (AppUpdates.trustedUrl(url, "/releases/download/") ||
                    uri.host in setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com"))
        }.getOrDefault(false)
    }
}
