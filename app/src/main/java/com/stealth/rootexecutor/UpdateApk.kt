package com.stealth.rootexecutor

import android.content.Context
import android.os.Build
import com.android.apksig.ApkVerifier
import java.io.File
import java.security.MessageDigest

internal data class UpdateIdentity(val packageName: String, val version: String?, val code: Long, val signers: Set<String>)

internal object UpdateApk {
    fun checkIdentity(installed: UpdateIdentity, incoming: UpdateIdentity, tag: String) {
        check(incoming.packageName == installed.packageName) { "更新包名不匹配。自定义包名副本请使用原应用生成新版副本。" }
        check(incoming.signers.isNotEmpty() && incoming.signers == installed.signers) { "更新安装包签名与当前应用不一致，已停止安装。" }
        check(incoming.code > installed.code) { "更新包的内部版本码未增加，无法覆盖当前应用。" }
        val remote = AppVersion.parse(incoming.version.orEmpty())
        val local = AppVersion.parse(installed.version.orEmpty()) ?: error("当前应用版本号无法识别")
        check(remote != null && remote == AppVersion.parse(tag)) { "更新安装包版本与发布信息不符。" }
        check(remote > local) { "更新安装包不是更新的版本。" }
    }

    @Suppress("DEPRECATION")
    fun verify(context: Context, apk: File, tag: String) {
        val verified = ApkVerifier.Builder(apk).setMinCheckedPlatformVersion(Build.VERSION.SDK_INT)
            .setMaxCheckedPlatformVersion(Build.VERSION.SDK_INT).build().verify()
        check(verified.isVerified) { "更新安装包签名校验失败，请重新下载。" }
        val manager = context.packageManager
        val incoming = manager.getPackageArchiveInfo(apk.path, 0) ?: error("无法解析更新安装包")
        val installed = manager.getPackageInfo(context.packageName, 0)
        // Verify both complete archives, rather than trusting unverified certificate metadata.
        val local = ApkVerifier.Builder(File(context.applicationInfo.sourceDir)).setMinCheckedPlatformVersion(Build.VERSION.SDK_INT)
            .setMaxCheckedPlatformVersion(Build.VERSION.SDK_INT).build().verify()
        check(local.isVerified) { "无法校验当前应用签名" }
        fun signers(result: ApkVerifier.Result) = result.signerCertificates.map { certificate ->
            MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it.toInt() and 255) }
        }.toSet()
        fun code(info: android.content.pm.PackageInfo) = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        checkIdentity(UpdateIdentity(installed.packageName, installed.versionName, code(installed), signers(local)),
            UpdateIdentity(incoming.packageName, incoming.versionName, code(incoming), signers(verified)), tag)
    }
}
