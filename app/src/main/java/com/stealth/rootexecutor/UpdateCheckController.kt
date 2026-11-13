package com.stealth.rootexecutor

import android.app.Activity
import android.content.Intent
import java.util.concurrent.Executors

/** Owns update-check work and navigation; the activity only renders its themed dialogs. */
internal class UpdateCheckController(private val activity: Activity, private val ui: Ui) : AutoCloseable {
    interface Ui {
        fun checking(busy: Boolean)
        fun message(title: String, detail: String)
        fun offer(installed: String, release: AppRelease, confirm: () -> Unit)
        fun failed(reason: String, retry: () -> Unit)
    }

    private val worker = Executors.newSingleThreadExecutor()
    var busy = false
        private set
    private var closed = false

    fun check() {
        if (busy || closed) return
        val installed = runCatching { activity.packageManager.getPackageInfo(activity.packageName, 0).versionName }.getOrNull()
        if (installed == null) { ui.message("检查更新失败", "无法读取当前应用版本。"); return }
        busy = true; ui.checking(true)
        worker.execute {
            val result = runCatching { GitHubUpdateChecker.check() }
            activity.runOnUiThread {
                busy = false
                if (closed || activity.isDestroyed || activity.isFinishing) return@runOnUiThread
                ui.checking(false)
                result.fold({ release ->
                    when {
                        release == null -> ui.message("暂无可用版本", "GitHub 暂无可识别的已发布版本。当前版本：$installed")
                        !AppUpdates.isNewer(release, installed) -> ui.message("暂无新版本", "当前版本：$installed\nGitHub 最新版本：${release.tag}${if (release.prerelease) "（测试版）" else ""}")
                        else -> ui.offer(installed, release) {
                            if (!closed && !activity.isDestroyed && !activity.isFinishing) {
                                if (release.apkUrl == null) check()
                                else if (SessionState.running || SessionState.startGate.isPreparing())
                                    ui.message("请先结束任务", "执行结束后再安装应用更新。")
                                else activity.startActivity(Intent(activity, AppUpdateActivity::class.java)
                                    .putExtra("tag", release.tag).putExtra("url", release.apkUrl)
                                    .putExtra("size", release.apkSize).putExtra("sha256", release.apkSha256))
                            }
                        }
                    }
                }, { error ->
                    val reason = when (error) {
                        is java.net.SocketTimeoutException -> "连接 GitHub 超时，请检查网络后重试。"
                        is java.io.IOException -> "无法连接 GitHub，请检查网络后重试。"
                        is org.json.JSONException -> "GitHub 返回的数据无法读取，请稍后重试。"
                        else -> error.message ?: "检查失败，请稍后重试。"
                    }
                    ui.failed(reason, ::check)
                })
            }
        }
    }

    override fun close() { closed = true; worker.shutdownNow() }
}
