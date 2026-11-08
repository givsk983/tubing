package com.stealth.rootexecutor

import android.content.Intent
import android.net.Uri
import android.os.Build

internal object SharedImport {
    fun accepts(intent: Intent) = intent.action in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE, Intent.ACTION_VIEW)

    @Suppress("DEPRECATION")
    fun uris(intent: Intent): List<Uri> {
        require(accepts(intent)) { "不支持的分享方式" }
        val streams = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> (if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)).orEmpty()
            else -> listOfNotNull(intent.data)
        }
        // Some providers only supply ClipData. Ignore text and never follow embedded Intents.
        val fallback = intent.clipData?.let { clip ->
            (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        }.orEmpty()
        val result = (streams.ifEmpty { fallback.ifEmpty { listOfNotNull(intent.data) } }).distinct()
        require(result.isNotEmpty()) { "未收到文件，请先下载文件，再选择分享或用其他应用打开" }
        require(result.size <= 100) { "一次最多分享 100 个文件" }
        require(result.all { it.scheme == "content" && !it.authority.isNullOrBlank() }) {
            "请通过来源应用的文件分享功能发送可读取的文件"
        }
        return result
    }
}
