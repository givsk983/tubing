package com.stealth.rootexecutor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import android.widget.ImageView
import java.io.File
import java.util.UUID

/** Generated fixtures only: decode actual Android images/APK icons and exercise recycled bindings. */
internal object FilePreviewChecks {
    fun run(context: Context, onUi: (() -> Unit) -> Unit): String {
        val root = RootExecutor()
        val id = UUID.randomUUID().toString()
        val fixture = File(context.cacheDir, "preview-check-$id").apply { mkdirs() }
        val protected = "${root.managedRoot}/tmp/preview-$id"
        var loader: BrowserThumbnails? = null
        try {
            fun image(name: String, color: Int): File = File(fixture, name).apply {
                val bitmap = Bitmap.createBitmap(30, 20, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(color)
                outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                bitmap.recycle()
            }
            val red = image("红色.png", Color.RED)
            val blue = image("blue.png", Color.BLUE)
            val apk = File(fixture, "own.apk.1").also { File(context.applicationInfo.sourceDir).copyTo(it) }
            lateinit var photo: ImageView
            lateinit var application: ImageView
            onUi { loader = BrowserThumbnails(context, root); photo = ImageView(context); application = ImageView(context) }
            val previews = checkNotNull(loader)
            fun entry(file: File, type: String = "f") = BrowserEntry(file.absolutePath, file.name, type, file.length(), file.lastModified())
            fun waitFor(view: ImageView, color: Int? = null) {
                var ready = false
                val deadline = SystemClock.elapsedRealtime() + 20_000
                while (!ready && SystemClock.elapsedRealtime() < deadline) {
                    onUi {
                        val bitmap = (view.drawable as? BitmapDrawable)?.bitmap
                        ready = view.imageTintList == null && bitmap != null &&
                            (color == null || bitmap.getPixel(bitmap.width / 2, bitmap.height / 2) == color)
                    }
                    if (!ready) SystemClock.sleep(50)
                }
                check(ready) { "File thumbnail did not match expected pixels / untinted APK icon" }
            }
            onUi { previews.bind(photo, entry(red), R.drawable.ic_file_image, Color.GREEN) }
            waitFor(photo, Color.RED)
            onUi {
                previews.bind(photo, entry(apk), R.drawable.ic_file_apk, Color.GREEN)
                previews.bind(application, entry(apk), R.drawable.ic_file_apk, Color.GREEN)
                previews.bind(photo, entry(blue), R.drawable.ic_file_image, Color.GREEN)
            }
            waitFor(application)
            waitFor(photo, Color.BLUE)
            SystemClock.sleep(200)
            waitFor(photo, Color.BLUE) // A slower APK completion cannot overwrite a recycled photo row.
            onUi { previews.clear(); previews.bind(photo, entry(red), R.drawable.ic_file_image, Color.GREEN) }
            waitFor(photo, Color.RED)
            check(root.initialize().code == 0)
            check(root.makeDirectory("${root.managedRoot}/tmp", "preview-$id").code == 0)
            check(root.copyPath(red.absolutePath, protected).code == 0)
            onUi { previews.bind(photo, BrowserEntry("$protected/${red.name}", red.name, "f", red.length(), 0), R.drawable.ic_file_image, Color.GREEN) }
            waitFor(photo, Color.RED)
            check(root.installApk("$protected/missing.apk.1").code == 46) { "Numbered APK was rejected before regular-file validation" }
            check(root.installApk("$protected/missing.akp.1").code == 46)
            check(context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("file-preview-") }) { "Preview snapshot leaked" }
            return "PASS: native PNG pixels, real APK.1 application icon, shared requests, recycled bindings, refresh, Root-only image, numbered-APK installation path and private snapshot cleanup"
        } finally {
            onUi { loader?.close() }
            root.deletePath(protected, true)
            fixture.deleteRecursively()
        }
    }
}
