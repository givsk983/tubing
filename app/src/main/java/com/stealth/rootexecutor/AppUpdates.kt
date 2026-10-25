package com.stealth.rootexecutor

import java.net.URI

/** Compare release tags numerically, including SemVer prerelease precedence. */
internal data class AppVersion(val numbers: List<Int>, val prerelease: List<String>) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int {
        numbers.zip(other.numbers).forEach { (left, right) ->
            if (left != right) return left.compareTo(right)
        }
        if (prerelease.isEmpty() || other.prerelease.isEmpty())
            return when {
                prerelease.isEmpty() && other.prerelease.isEmpty() -> 0
                prerelease.isEmpty() -> 1
                else -> -1
            }
        prerelease.zip(other.prerelease).forEach { (left, right) ->
            val leftNumeric = left.all(Char::isDigit)
            val rightNumeric = right.all(Char::isDigit)
            val result = when {
                leftNumeric && rightNumeric -> left.length.compareTo(right.length).takeIf { it != 0 } ?: left.compareTo(right)
                leftNumeric -> -1
                rightNumeric -> 1
                else -> left.compareTo(right)
            }
            if (result != 0) return result
        }
        return prerelease.size.compareTo(other.prerelease.size)
    }

    companion object {
        fun parse(tag: String): AppVersion? {
            val match = Regex("^[vV]?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$")
                .matchEntire(tag.trim()) ?: return null
            val numbers = (1..3).map { match.groupValues[it].toIntOrNull() ?: return null }
            val prerelease = match.groupValues[4].takeIf { it.isNotEmpty() }?.split('.') ?: emptyList()
            if (prerelease.any { it.length > 1 && it.startsWith('0') && it.all(Char::isDigit) }) return null
            return AppVersion(numbers, prerelease)
        }
    }
}

internal data class AppRelease(
    val tag: String,
    val title: String,
    val notes: String,
    val pageUrl: String,
    val apkUrl: String?,
    val draft: Boolean = false,
    val prerelease: Boolean = false
)

internal object AppUpdates {
    const val REPOSITORY = "givsk983/tubing"
    const val RELEASES_URL = "https://github.com/$REPOSITORY/releases"

    fun latest(releases: List<AppRelease>): AppRelease? = releases.asSequence()
        .filter { !it.draft && trustedUrl(it.pageUrl, "/releases/tag/") }
        .mapNotNull { release -> AppVersion.parse(release.tag)?.let { release to it } }
        .maxByOrNull { it.second }?.first

    fun isNewer(release: AppRelease, installed: String): Boolean {
        val remote = AppVersion.parse(release.tag) ?: error("GitHub 版本号无法识别")
        val local = AppVersion.parse(installed) ?: error("当前应用版本号无法识别")
        return remote > local
    }

    fun trustedUrl(url: String, path: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.host == "github.com" && uri.userInfo == null &&
            uri.port == -1 && uri.path.startsWith("/$REPOSITORY$path") &&
            uri.path.removePrefix("/$REPOSITORY$path").isNotBlank()
    }.getOrDefault(false)
}
