package com.stealth.rootexecutor

import android.app.*
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors

/** Root/shell-only entry point. Never accepts arbitrary commands or script paths. */
class AutomationService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var preparing = false
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("automation", "App 自动执行", NotificationManager.IMPORTANCE_LOW))
        startForeground(8, Notification.Builder(this, "automation")
            .setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("Tubing · 正在检查 App 绑定").build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (preparing) return START_NOT_STICKY
        val pkg = intent?.getStringExtra("package") ?: ""
        val targetUser = intent?.getIntExtra("profileUser", 0) ?: 0
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        if (targetUser < 0 || !AppAutomation.validPackage(pkg) || !AppAutomation.bindings(prefs).containsKey(pkg)) {
            stopSelf(); return START_NOT_STICKY
        }
        val blocked = AppAutomation.blocked(prefs, pkg)
        if (SessionState.running || blocked != null || !SessionState.startGate.tryPrepare(SystemClock.elapsedRealtime())) {
            AppAutomation.status(this, blocked ?: "已有任务或正在准备，已跳过")
            stopSelf(); return START_NOT_STICKY
        }
        AppAutomation.status(this, "正在检查绑定与前台状态")
        preparing = true
        worker.execute {
            val resolved = runCatching { AppAutomation.resolve(this, pkg) }
            main.post {
                SessionState.startGate.finishPreparation()
                preparing = false
                try {
                    val plan = resolved.getOrThrow()
                    check(AppAutomation.blocked(prefs, pkg) == null) { "绑定已进入冷却或单次启用已使用" }
                    if (!SessionState.running && SessionState.startGate.tryStart(SystemClock.elapsedRealtime())) {
                        startForegroundService(Intent(this, ExecutionService::class.java)
                            .putExtra("kind", plan.file.kind).putExtra("name", plan.file.name)
                            .putExtra("automationPackage", pkg).putExtra("automationShortcut", plan.shortcut)
                            .putExtra("profileUser", targetUser)
                            .putExtra("timeoutSeconds", AppAutomation.policy(prefs, pkg).timeoutSeconds)
                            .putExtra("rememberInput", false).putExtra("trackInputHistory", false)
                            .putStringArrayListExtra("inputPrompts", ArrayList(plan.prompts))
                            .putExtra("inputSendMode", plan.sendMode.key)
                            .putStringArrayListExtra("sensitiveValues", ArrayList(if (plan.sensitive) plan.steps else emptyList()))
                            .putStringArrayListExtra("inputSteps", ArrayList(plan.steps)))
                        Log.i("TubingAutomation", "Triggered $pkg")
                    } else {
                        AppAutomation.status(this, "已有任务启动，已跳过")
                    }
                } catch (e: Exception) {
                    SessionState.startGate.resetAfterFailure()
                    Log.e("TubingAutomation", "Cannot trigger $pkg")
                    val reason = if (e is IllegalStateException) e.message?.take(160) ?: "自动执行检查失败" else "方案无法读取，请检查配置或 Root 授权"
                    AppAutomation.status(this, reason, failed = true)
                    SessionState.output("\r\n[App 自动执行] $reason\r\n")
                } finally {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() { worker.shutdown(); super.onDestroy() }
}
