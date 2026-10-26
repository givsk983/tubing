package com.stealth.rootexecutor

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.util.zip.ZipInputStream

internal data class ModuleBundle(val version: String, val bytes: ByteArray) {
    val fileName get() = "tubing-app-trigger-v$version.zip"
}

internal object BundledModule {
    fun read(context: Context): ModuleBundle {
        val bytes = context.assets.open("tubing-app-trigger.zip").use { it.readBytes() }
        var properties: String? = null
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "module.prop") { properties = zip.readBytes().toString(Charsets.UTF_8); break }
            }
        }
        val values = requireNotNull(properties) { "内置模块缺少版本信息" }.lineSequence()
            .filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=').trim() }
        require(values["id"] == "tubing_app_trigger") { "内置模块标识无效" }
        val version = requireNotNull(values["version"])
        require(version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")))
        return ModuleBundle(version, bytes)
    }

    /** User initiated export; MediaStore ownership avoids storage/Root permission. */
    fun exportDownloads(context: Context, bundle: ModuleBundle): Uri {
        if (Build.VERSION.SDK_INT < 29) error("请使用系统文件选择器保存模块")
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val directory = "${Environment.DIRECTORY_DOWNLOADS}/Tubing/"
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=?"
        val existing = resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), selection,
            arrayOf(bundle.fileName, directory, context.packageName), null)?.use {
            if (it.moveToFirst()) Uri.withAppendedPath(collection, it.getLong(0).toString()) else null
        }
        if (existing != null) {
            val matches = runCatching { resolver.openInputStream(existing)?.use { it.readBytes().contentEquals(bundle.bytes) } == true }.getOrDefault(false)
            if (matches) return existing
            resolver.openOutputStream(existing, "rwt")?.use { it.write(bundle.bytes) } ?: error("模块安装包更新失败")
            return existing
        }
        val uri = resolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, bundle.fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
            put(MediaStore.MediaColumns.RELATIVE_PATH, directory)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }) ?: error("无法创建模块安装包")
        try {
            resolver.openOutputStream(uri, "w")?.use { it.write(bundle.bytes) } ?: error("模块安装包写入失败")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    fun managerIntent(context: Context, pkg: String, uri: Uri): Intent? {
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/zip").setPackage(pkg)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { clipData = ClipData.newRawUri("Tubing module", uri) }
        if (context.packageManager.resolveActivity(view, 0) != null) return view
        return context.packageManager.getLaunchIntentForPackage(pkg)
    }
}
