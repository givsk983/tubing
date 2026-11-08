package com.stealth.rootexecutor

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.ThumbnailUtils
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.lang.ref.WeakReference
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.WeakHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Only visible rows request previews. Coalescing and bind checks prevent recycled-row mixups. */
internal class BrowserThumbnails(private val context: Context, private val root: RootExecutor) {
    private val main = Handler(Looper.getMainLooper())
    private val executor = ThreadPoolExecutor(2, 2, 15, TimeUnit.SECONDS, LinkedBlockingQueue(48))
    private data class Preview(val bitmap: Bitmap?)
    private val cache = LruCache<String, Preview>(128)
    private val bindings = WeakHashMap<ImageView, String>()
    private val pending = mutableMapOf<String, Request>()
    private val size = (24 * context.resources.displayMetrics.density).toInt().coerceIn(24, 128)
    @Volatile private var generation = 0
    @Volatile private var closed = false

    private inner class Request(val key: String, val entry: BrowserEntry, val epoch: Int) : Runnable {
        val targets = mutableListOf<WeakReference<ImageView>>()
        @Volatile var cancelled = false
        override fun run() {
            if (cancelled || closed || epoch != generation) return
            val preview = Preview(runCatching { load(entry) }.getOrNull())
            main.post {
                if (!closed && !cancelled && epoch == generation && pending[key] === this) {
                    pending.remove(key)
                    cache.put(key, preview)
                    targets.forEach { reference -> reference.get()?.let { view ->
                        if (bindings[view] == key) apply(view, preview)
                    } }
                }
            }
        }
    }

    fun bind(view: ImageView, entry: BrowserEntry, fallback: Int, tint: Int) {
        val key = "${entry.path}\u0000${entry.size}\u0000${entry.modified}"
        val previous = bindings.put(view, key)
        if (previous != null && previous != key) pending[previous]?.let { request ->
            request.targets.removeAll { it.get() == null || it.get() === view }
            if (request.targets.isEmpty()) {
                request.cancelled = true; executor.remove(request); pending.remove(previous)
            }
        }
        view.imageTintList = ColorStateList.valueOf(tint)
        view.setImageResource(fallback)
        if (closed || entry.type != "f" || (!BrowserFileTypes.isApk(entry.name) && !BrowserFileTypes.isImage(entry.name))) return
        cache.get(key)?.let { apply(view, it); return }
        pending[key]?.let { request ->
            if (request.targets.none { it.get() === view }) request.targets.add(WeakReference(view))
            return
        }
        val request = Request(key, entry, generation).apply { targets.add(WeakReference(view)) }
        pending[key] = request
        try { executor.execute(request) } catch (_: RejectedExecutionException) { pending.remove(key) }
    }

    fun unbind(view: ImageView) {
        val key = bindings.remove(view) ?: return
        pending[key]?.let { request ->
            request.targets.removeAll { it.get() == null || it.get() === view }
            if (request.targets.isEmpty()) {
                request.cancelled = true; executor.remove(request); pending.remove(key)
            }
        }
    }

    private fun apply(view: ImageView, preview: Preview) {
        preview.bitmap?.let { view.imageTintList = null; view.setImageBitmap(it) }
    }

    private fun load(entry: BrowserEntry): Bitmap? {
        val apk = BrowserFileTypes.isApk(entry.name)
        val source = File(entry.path)
        val limit = if (apk) 512L * 1024 * 1024 else 64L * 1024 * 1024
        if (Files.isSymbolicLink(source.toPath())) return null
        if (Files.isRegularFile(source.toPath(), LinkOption.NOFOLLOW_LINKS) && source.canRead() && source.length() <= limit) {
            val direct = runCatching { decode(source, apk) }.getOrNull()
            if (direct != null) return direct
        }
        if (closed || Thread.currentThread().isInterrupted) return null
        val snapshot = root.prepareFilePreview(entry.path, context.cacheDir, apk)
        return try { decode(snapshot, apk) } finally { snapshot.delete() }
    }

    private fun decode(file: File, apk: Boolean): Bitmap? {
        if (apk) {
            val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0) ?: return null
            val application = info.applicationInfo ?: return null
            application.sourceDir = file.absolutePath
            application.publicSourceDir = file.absolutePath
            val icon = application.loadIcon(context.packageManager)
            return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
                icon.setBounds(0, 0, size, size); icon.draw(Canvas(bitmap))
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > size * 2) sample *= 2
        val decoded = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: return null
        val orientation = runCatching { ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
        val matrix = Matrix().apply {
            when (orientation) {
                2 -> setScale(-1f, 1f)
                3 -> setRotate(180f)
                4 -> setScale(1f, -1f)
                5 -> { setRotate(90f); postScale(-1f, 1f) }
                6 -> setRotate(90f)
                7 -> { setRotate(90f); postScale(1f, -1f) }
                8 -> setRotate(270f)
            }
        }
        val oriented = if (matrix.isIdentity) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (oriented !== decoded) decoded.recycle()
        return ThumbnailUtils.extractThumbnail(oriented, size, size, ThumbnailUtils.OPTIONS_RECYCLE_INPUT)
    }

    fun clear() {
        generation++
        pending.values.forEach { it.cancelled = true }
        pending.clear(); executor.queue.clear(); cache.evictAll()
    }

    fun close() {
        closed = true; clear(); executor.shutdownNow()
        bindings.clear(); main.removeCallbacksAndMessages(null)
    }
}
