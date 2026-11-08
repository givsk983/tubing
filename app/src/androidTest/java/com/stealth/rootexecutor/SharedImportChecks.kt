package com.stealth.rootexecutor

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Android parcelable extraction and actual installed manifest resolution; no user programs run. */
internal object SharedImportChecks {
    fun run(context: Context): String {
        val first = Uri.parse("content://share.fixture/files/项目.zip")
        val second = Uri.parse("content://share.fixture/files/run")
        fun rejected(intent: Intent) = check(runCatching { SharedImport.uris(intent) }.isFailure)
        check(SharedImport.uris(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, first)) == listOf(first))
        val clip = ClipData.newRawUri("file", first).apply { addItem(ClipData.Item(second)) }
        check(SharedImport.uris(Intent(Intent.ACTION_SEND).apply { clipData = clip }) == listOf(first, second))
        check(SharedImport.uris(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, first).apply { clipData = clip }) == listOf(first))
        check(SharedImport.uris(Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(
            Intent.EXTRA_STREAM, arrayListOf(first, second, first))) == listOf(first, second))
        check(SharedImport.uris(Intent(Intent.ACTION_VIEW, first)) == listOf(first))
        check(SharedImport.uris(Intent(Intent.ACTION_SEND).setData(first)) == listOf(first))
        rejected(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "https://example.com/run.sh"))
        rejected(Intent(Intent.ACTION_VIEW, Uri.parse("file:///data/private.sh")))
        rejected(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/run.sh")))
        rejected(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, "not a Uri"))
        rejected(Intent(Intent.ACTION_MAIN, first))
        rejected(Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM,
            ArrayList((0..100).map { Uri.parse("content://share.fixture/$it") })))
        for (action in listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE, Intent.ACTION_VIEW)) {
            for (mime in listOf("application/zip", "application/octet-stream", "text/x-shellscript", "text/plain")) {
                val intent = Intent(action).setDataAndType(if (action == Intent.ACTION_VIEW) first else null, mime)
                    .setPackage(context.packageName).addCategory(Intent.CATEGORY_DEFAULT)
                val matches = context.packageManager.queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                check(matches.any { it.activityInfo.name == MainActivity::class.java.name }) { "Missing receiver for $action / $mime" }
            }
        }
        return "SEND / SEND_MULTIPLE / VIEW, stream / ClipData / data fallback, deduplication, invalid URI rejection and 12 manifest matches passed"
    }
}
