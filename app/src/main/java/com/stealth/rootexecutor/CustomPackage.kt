package com.stealth.rootexecutor

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.security.auth.x500.X500Principal

internal object CustomPackage {
    private const val ALIAS = "tubing.custom.package.signer.v1"

    fun create(context: Context, target: String): File {
        require(PackageManifest.validName(target) && target != context.packageName)
        check(context.applicationInfo.splitSourceDirs.isNullOrEmpty()) { "暂不支持拆分安装包，请使用完整 APK" }
        val directory = File(context.cacheDir, "custom-package").apply { mkdirs() }
        val unsigned = File(directory, "unsigned.apk")
        val signed = File(directory, "$target.apk")
        try {
            PackageArchive.rewrite(File(context.applicationInfo.sourceDir), unsigned, context.packageName, target)
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!store.containsAlias(ALIAS)) {
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
                    initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                        .setKeySize(2048).setDigests(KeyProperties.DIGEST_SHA256)
                        .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                        .setCertificateSubject(X500Principal("CN=Tubing Custom Package")).build())
                }.generateKeyPair()
            }
            val signer = ApkSigner.SignerConfig.Builder("tubing", store.getKey(ALIAS, null) as PrivateKey,
                listOf(store.getCertificate(ALIAS) as X509Certificate)).build()
            ApkSigner.Builder(listOf(signer)).setInputApk(unsigned).setOutputApk(signed)
                .setMinSdkVersion(26).setV1SigningEnabled(false).setV2SigningEnabled(true)
                .setV3SigningEnabled(false).setV4SigningEnabled(false).build().sign()
            check(ApkVerifier.Builder(signed).setMinCheckedPlatformVersion(26).build().verify().isVerified) { "副本签名校验失败" }
            val info = context.packageManager.getPackageArchiveInfo(signed.path, 0)
            check(info?.packageName == target) { "生成包名校验失败" }
            return signed
        } catch (e: Exception) {
            signed.delete()
            throw e
        } finally { unsigned.delete() }
    }

    fun install(root: RootExecutor, apk: File, target: String): CommandResult {
        require(PackageManifest.validName(target))
        // Never overwrite an existing installation (including one hidden by package visibility).
        return root.runBounded("""
            set -eu
            if pm path --user 0 ${root.quote(target)} | grep -q '^package:'; then
              echo '该包名已安装，请选择其他包名'; exit 1
            fi
            tmp=${'$'}(mktemp /data/local/tmp/tubing-clone.XXXXXX)
            trap 'rm -f "${'$'}tmp"' EXIT
            cp ${root.quote(apk.absolutePath)} "${'$'}tmp"
            chmod 644 "${'$'}tmp"
            pm install --user 0 "${'$'}tmp"
        """.trimIndent(), 60)
    }
}
