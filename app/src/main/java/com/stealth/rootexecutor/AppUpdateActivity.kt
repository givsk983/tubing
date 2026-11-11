package com.stealth.rootexecutor

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.provider.Settings
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.Executors

/** Downloads stay inside Tubing; only Android's permission and install confirmation UI is opened. */
class AppUpdateActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private var download: UpdateDownload? = null
    private var apk: File? = null
    private var busy = false
    private var installerOpened = false
    private lateinit var release: AppRelease
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var action: Button
    private lateinit var close: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        val appearance = getSharedPreferences("settings", MODE_PRIVATE).getString("theme", "system")
        val dark = appearance == "dark" || (appearance == "system" &&
            resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
        setTheme(if (dark) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar)
        super.onCreate(savedInstanceState)
        QuickExecutionExit.updateOpened()
        val palette = UiPalette.forTheme(dark)
        release = AppRelease(intent.getStringExtra("tag").orEmpty(), "", "", "", intent.getStringExtra("url"),
            apkSize = intent.getLongExtra("size", 0), apkSha256 = intent.getStringExtra("sha256"))
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(palette.background)
            fitsSystemWindows = true
        }
        body.addView(TextView(this).apply { text = "更新 Tubing ${release.tag}"; textSize = 24f; setTextColor(palette.text) })
        status = TextView(this).apply { textSize = 15f; setTextColor(palette.muted); setPadding(0, 24, 0, 24) }
        body.addView(status)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        body.addView(progress, LinearLayout.LayoutParams(-1, -2))
        action = Button(this)
        body.addView(action, LinearLayout.LayoutParams(-1, -2))
        close = Button(this).apply { text = "取消下载"; setOnClickListener { finish() } }
        body.addView(close, LinearLayout.LayoutParams(-1, -2))
        setContentView(body)
        apk = savedInstanceState?.getString("apk")?.let(::File)?.takeIf {
            it.isFile && it.extension == "apk" && it.canonicalFile.parentFile == File(cacheDir, "updates").canonicalFile
        }
        if (apk != null) showReady("安装包已下载，点击继续安装。") else startDownload()
    }

    private fun startDownload() {
        if (busy) return
        busy = true
        action.visibility = View.GONE
        close.text = "取消下载"
        status.text = "正在连接下载服务器…"
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true
        val task = UpdateDownload().also { download = it }
        worker.execute {
            val result = runCatching {
                // Remove abandoned files from previous process deaths without touching current installs.
                val directory = File(cacheDir, "updates")
                directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 24 * 60 * 60 * 1000L }
                    ?.forEach { it.delete() }
                var lastPercent = -1
                var lastTime = 0L
                val file = task.download(release, directory) { received, total ->
                    val percent = if (total > 0) (received * 100 / total).toInt() else -1
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastTime >= 200 || (percent != lastPercent && percent == 100)) {
                        lastTime = now; lastPercent = percent
                        runOnUiThread {
                            if (!isDestroyed && !isFinishing) {
                                progress.isIndeterminate = total <= 0
                                if (total > 0) progress.progress = percent
                                status.text = if (total > 0) "正在下载 $percent%（${received / 1024} / ${total / 1024} KB）"
                                    else "正在下载（${received / 1024} KB）"
                            }
                        }
                    }
                }
                try {
                    runOnUiThread { if (!isDestroyed && !isFinishing) status.text = "正在校验安装包…" }
                    UpdateApk.verify(this, file, release.tag)
                    file
                } catch (error: Exception) { file.delete(); throw error }
            }
            runOnUiThread {
                busy = false
                if (isDestroyed || isFinishing) { result.getOrNull()?.delete(); return@runOnUiThread }
                result.fold({ file ->
                    apk = file
                    showReady("下载和校验完成，准备安装更新。")
                    requestInstall()
                }, { error ->
                    progress.visibility = View.GONE
                    status.text = "更新未完成：${error.message ?: "请检查网络后重试。"}"
                    close.text = "关闭"
                    action.visibility = View.VISIBLE
                    action.text = "重新下载"
                    action.setOnClickListener { startDownload() }
                })
            }
        }
    }

    private fun showReady(message: String) {
        status.text = message
        progress.visibility = View.GONE
        close.text = "稍后安装"
        action.visibility = View.VISIBLE
        action.isEnabled = true
        action.text = "安装更新"
        action.setOnClickListener { requestInstall() }
    }

    @Suppress("DEPRECATION")
    private fun requestInstall() {
        if (busy) return
        if (SessionState.running || SessionState.startGate.isPreparing()) {
            showReady("请先结束正在执行的任务，再点击安装更新。")
            return
        }
        val file = apk ?: return
        busy = true
        action.isEnabled = false
        status.text = "正在校验安装包…"
        worker.execute {
            val result = runCatching { UpdateApk.verify(this, file, release.tag) }
            runOnUiThread {
                busy = false
                if (isDestroyed || isFinishing) return@runOnUiThread
                result.fold({
                    showReady("请在系统安装界面确认更新，应用数据会保留。")
                    if (SessionState.running || SessionState.startGate.isPreparing()) {
                        showReady("请先结束正在执行的任务，再点击安装更新。")
                    } else if (!packageManager.canRequestPackageInstalls()) {
                        status.text = "首次更新需要允许 Tubing 安装应用；授权后返回即可继续安装。"
                        action.text = "允许安装并继续"
                        action.setOnClickListener {
                            runCatching { startActivityForResult(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:$packageName")), 201) }
                                .onFailure { status.text = "无法打开授权页面，请在系统设置中允许 Tubing 安装未知应用，然后重试。" }
                        }
                    } else {
                        runCatching {
                            val uri = FileProvider.getUriForFile(this, "$packageName.updates", file)
                            val install = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                                data = uri
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                putExtra(Intent.EXTRA_RETURN_RESULT, true)
                                clipData = android.content.ClipData.newRawUri("Tubing update", uri)
                            }
                            startActivityForResult(install, 202)
                            installerOpened = true
                            action.isEnabled = false
                        }.onFailure { showReady("无法打开系统安装界面：${it.message ?: "请稍后重试。"}") }
                    }
                }, { error ->
                    file.delete(); apk = null
                    status.text = "安装包校验失败：${error.message ?: "请重新下载。"}"
                    close.text = "关闭"
                    action.isEnabled = true
                    action.text = "重新下载"
                    action.setOnClickListener { startDownload() }
                })
            }
        }
    }

    @Deprecated("Legacy activity result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 201) {
            if (packageManager.canRequestPackageInstalls()) requestInstall()
            else showReady("尚未允许安装应用。点击安装更新可重新授权，无需重新下载。")
        } else if (requestCode == 202) {
            installerOpened = false
            if (resultCode == RESULT_OK) { apk?.delete(); finish() }
            else showReady("安装未完成或已取消。可重试安装，无需重新下载。")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        apk?.let { outState.putString("apk", it.absolutePath) }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        download?.cancel()
        worker.shutdownNow()
        if (isFinishing && !installerOpened) apk?.delete()
        QuickExecutionExit.updateClosed()
        super.onDestroy()
    }
}
