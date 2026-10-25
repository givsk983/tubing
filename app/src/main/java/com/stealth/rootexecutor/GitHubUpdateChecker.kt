package com.stealth.rootexecutor

import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

internal object GitHubUpdateChecker {
    // Use the release list: GitHub's /latest endpoint omits prereleases.
    fun check(): AppRelease? {
        val connection = URL("https://api.github.com/repos/${AppUpdates.REPOSITORY}/releases?per_page=100")
            .openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connection.setRequestProperty("User-Agent", "Tubing-Update-Checker")
            connection.useCaches = false
            when (val status = connection.responseCode) {
                200 -> Unit
                403, 429 -> error("GitHub 暂时限制了请求，请稍后再试。")
                404 -> error("未找到 GitHub 发布仓库，请稍后再试。")
                else -> error("GitHub 请求失败（HTTP $status），请稍后再试。")
            }
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= 2 * 1024 * 1024) { "GitHub 返回内容过大，请前往发布页查看。" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            return parse(String(bytes, Charsets.UTF_8))
        } finally {
            connection.disconnect()
        }
    }

    internal fun parse(json: String): AppRelease? {
        val releases = JSONArray(json)
        return AppUpdates.latest((0 until releases.length()).map { index ->
            val release = releases.getJSONObject(index)
            val assets = release.optJSONArray("assets") ?: JSONArray()
            val tag = release.optString("tag_name")
            val apkName = "tubing-v${tag.removePrefix("v").removePrefix("V")}.apk"
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull {
                it.optString("name") == apkName && it.optString("state") == "uploaded" &&
                    AppUpdates.trustedUrl(it.optString("browser_download_url"), "/releases/download/")
            }
            AppRelease(tag, release.optString("name").ifBlank { tag }, if (release.isNull("body")) "" else release.optString("body"),
                release.optString("html_url"), apk?.optString("browser_download_url"),
                release.optBoolean("draft"), release.optBoolean("prerelease"))
        })
    }
}
