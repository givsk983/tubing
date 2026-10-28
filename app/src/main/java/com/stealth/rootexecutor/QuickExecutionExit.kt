package com.stealth.rootexecutor

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.widget.Toast

/** Main-thread only; no force-stop, so later companion-module requests can still launch Tubing. */
internal object QuickExecutionExit {
    const val SETTING = "exitAfterQuickExecution"
    const val SHORTCUT = "quickExecution"
    private val request = QuickExitRequest()
    private val main = Handler(Looper.getMainLooper())

    fun cancel() { request.cancel() }
    fun pending() = request.matches(SessionState.executionId)
    fun complete(context: Context, id: String, shortcut: Boolean) {
        request.complete(id, shortcut, context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean(SETTING, false))
    }

    fun afterServiceDestroyed(context: Context) {
        val id = SessionState.executionId
        if (!request.matches(id)) return
        val app = context.applicationContext
        main.postDelayed({
            if (!request.canExit(SessionState.executionId,
                    app.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean(SETTING, false),
                    SessionState.running, SessionState.startGate.isPreparing(), ExecutionService.active != null) ||
                !request.matches(id)) return@postDelayed
            // commit waits for earlier apply writes before the process is terminated.
            val saved = listOf("settings", "execution_history", "automation_status").all {
                app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().commit()
            }
            if (!saved) {
                request.cancel()
                Toast.makeText(app, "记录保存失败，已保留应用后台，请稍后重试", Toast.LENGTH_LONG).show()
                return@postDelayed
            }
            app.getSystemService(ActivityManager::class.java).appTasks.forEach { it.finishAndRemoveTask() }
            Process.killProcess(Process.myPid())
        }, 200)
    }
}
