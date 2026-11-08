package com.stealth.rootexecutor

internal object BrowserFileTypes {
    private val numberedApk = Regex("\\.(?:apk|akp)\\.[0-9]+$", RegexOption.IGNORE_CASE)
    private val images = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif")

    fun isApk(name: String) = name.endsWith(".apk", true) || numberedApk.containsMatchIn(name)
    fun isImage(name: String) = name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT) in images
}
