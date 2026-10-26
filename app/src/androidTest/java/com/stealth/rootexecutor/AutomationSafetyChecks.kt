package com.stealth.rootexecutor

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import java.io.File
import java.util.UUID

/** Uses only generated benign scripts; never runs the user's shortcuts. */
internal object AutomationSafetyChecks {
    fun monitorRecovery(): String {
        val result = RootExecutor().runBounded("""
            set -eu
            d=/data/adb/modules/tubing_app_trigger
            before=${'$'}(sed -n 's/^pid=//p' "${'$'}d/status.log")
            supervisor=${'$'}(cat "${'$'}d/supervisor.pid")
            case "${'$'}before:${'$'}supervisor" in *[!0-9:]*|:*) exit 1 ;; esac
            tr '\000' '\n' < /proc/${'$'}before/cmdline | grep -Fxq "${'$'}d/monitor.sh"
            tr '\000' '\n' < /proc/${'$'}supervisor/cmdline | grep -Fxq "${'$'}d/supervisor.sh"
            kill "${'$'}before"
            sleep 9
            after=${'$'}(sed -n 's/^pid=//p' "${'$'}d/status.log")
            [ "${'$'}after" != "${'$'}before" ]
            kill -0 "${'$'}after"
            kill -0 "${'$'}supervisor"
            echo "PASS: monitor recovered ${'$'}before -> ${'$'}after; supervisor=${'$'}supervisor"
            cat "${'$'}d/heartbeat"
        """.trimIndent(), 15)
        check(result.code == 0 && result.output.contains("PASS:")) { result.output }
        return result.output
    }
    fun run(context: Context): List<String> {
        check(!SessionState.running) { "请先停止正在运行的任务" }
        val root = RootExecutor()
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val id = UUID.randomUUID().toString()
        val inputKey = "scriptInput_presets_safety_$id"
        val otherKey = "scriptInput_history_safety_$id"
        val pkg = "com.example.safetytest"
        val oldBindings = AppAutomation.bindings(prefs)
        val oldLogging = prefs.getBoolean("saveLogs", false)
        val oldOptions = prefs.getString("appAutomationOptions", "{}")
        val oldShortcuts = prefs.getString("quickShortcuts", "[]")
        var generatedPresetKey: String? = null
        val historyPrefs = context.getSharedPreferences("execution_history", Context.MODE_PRIVATE)
        val oldHistory = historyPrefs.getString("records", "[]")
        val statusPrefs = context.getSharedPreferences("automation_status", Context.MODE_PRIVATE)
        val oldStatus = statusPrefs.getString("message", null)
        val oldStatusTime = statusPrefs.getLong("time", 0)
        val records = mutableListOf<String>()
        val files = mutableListOf<ManagedFile>()
        val checks = mutableListOf<String>()
        val secret = "safety-secret-$id"
        fun install(suffix: String, script: String): ManagedFile {
            val name = "automation-$id-$suffix.sh"
            val cache = File(context.cacheDir, name).apply { writeText(script) }
            try {
                val path = "${root.managedRoot}/$name"
                val result = root.runBounded("[ ! -e ${root.quote(path)} ] && cp ${root.quote(cache.absolutePath)} ${root.quote(path)} && chmod 700 ${root.quote(path)}")
                check(result.code == 0) { result.output }
            } finally { cache.delete() }
            return ManagedFile("library", name, "700").also { files.add(it) }
        }
        fun execute(file: ManagedFile, configure: Intent.() -> Unit = {}): ExecutionRecord {
            val idleDeadline = SystemClock.elapsedRealtime() + 5_000
            while (ExecutionService.active != null && !SessionState.running && SystemClock.elapsedRealtime() < idleDeadline) SystemClock.sleep(25)
            check(!SessionState.running && ExecutionService.active == null) { "上一个测试服务尚未销毁" }
            val before = SessionState.executionId
            context.startForegroundService(Intent(context, ExecutionService::class.java)
                .putExtra("kind", file.kind).putExtra("name", file.name).putExtra("mode", "pipe")
                .putExtra("timeoutSeconds", 8).apply(configure))
            val deadline = SystemClock.elapsedRealtime() + 25_000
            while (SystemClock.elapsedRealtime() < deadline) {
                if (SessionState.executionId != before && !SessionState.running && ExecutionService.active == null) break
                SystemClock.sleep(50)
            }
            check(!SessionState.running && SessionState.executionId != before && ExecutionService.active == null) {
                val stacks = Thread.getAllStackTraces().entries.filter {
                    it.key.name == "main" || it.key.name.startsWith("pool-") || it.key.name.contains("pipe")
                }.joinToString("\n") { (thread, trace) -> "${thread.name}: ${trace.take(8).joinToString(" <- ")}" }
                "安全测试任务未按时完成：${SessionState.status}; active=${ExecutionService.active != null}\n$stacks"
            }
            return ExecutionHistory.read(context).first { it.id == SessionState.executionId }.also { records.add(it.id) }
        }
        try {
            PrivateInputStore.write(prefs, inputKey, "[\"$secret\"]")
            check(!prefs.getString(inputKey, "")!!.contains(secret))
            check(PrivateInputStore.read(prefs, inputKey) == "[\"$secret\"]")
            prefs.edit().putString(otherKey, prefs.getString(inputKey, "")).commit()
            check(runCatching { PrivateInputStore.read(prefs, otherKey) }.isFailure)
            prefs.edit().putString(otherKey, "[\"legacy-input\"]").commit()
            check(PrivateInputStore.read(prefs, otherKey) == "[\"legacy-input\"]")
            check(prefs.getString(otherKey, "")!!.startsWith("enc:v1:"))
            checks.add("Keystore roundtrip, AAD substitution rejected, plaintext migration")

            AppAutomation.savePolicy(prefs, pkg, AutomationPolicy(60, 8, true))
            AppAutomation.markAttempt(prefs, pkg)
            check(AppAutomation.blocked(prefs, pkg) != null)
            AppAutomation.rearm(prefs, pkg)
            check(AppAutomation.blocked(prefs, pkg) == null)
            checks.add("Persistent one-shot/cooldown and explicit rearm")

            prefs.edit().putBoolean("saveLogs", true).commit()
            val prompt = install("prompt", """
                #!/system/bin/sh
                printf 'READY> '
                read first
                printf '\nECHO:%s\n' "${'$'}first"
                printf 'NEXT> '
                read second
                [ "${'$'}second" = done ] || exit 9
                echo fixture-ok
            """.trimIndent())
            val success = execute(prompt) {
                putExtra("inputSendMode", "prompt")
                putStringArrayListExtra("inputSteps", arrayListOf(secret, "done"))
                putStringArrayListExtra("inputPrompts", arrayListOf("READY>", "NEXT>"))
                putStringArrayListExtra("sensitiveValues", arrayListOf(secret, "done"))
            }
            check(success.exitCode == 0 && success.status.contains("退出码 0")) { "提示匹配测试失败：${success.status}" }
            val output = StringBuilder()
            val observer: (String) -> Unit = { output.append(it) }
            SessionState.subscribe(observer); SessionState.unsubscribe(observer)
            check(!output.contains(secret) && output.contains("[已隐藏]")) { "敏感输出未脱敏" }
            check(!File(context.cacheDir, "${success.id}.log").exists())
            check(root.runBounded("[ ! -e ${root.quote("${root.managedRoot}/logs/${success.id}.log")} ]").code == 0)
            checks.add("Real Pipe prompt sequencing; sensitive echo redacted; no cache/root output log")

            val encoded = android.util.Base64.encodeToString("${prompt.kind}/${prompt.name}".toByteArray(Charsets.UTF_8),
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
            generatedPresetKey = "scriptInput_presets_$encoded"
            PrivateInputStore.write(prefs, generatedPresetKey, org.json.JSONArray().put(org.json.JSONObject()
                .put("name", "legacy-fixture").put("steps", org.json.JSONArray(listOf(secret, "done")))).toString())
            prefs.edit().putString("quickShortcuts", org.json.JSONArray(oldShortcuts).put(org.json.JSONObject()
                .put("name", "safety-fixture").put("kind", prompt.kind).put("fileName", prompt.name).put("presetName", "legacy-fixture")).toString()).commit()
            AppAutomation.save(prefs, oldBindings + (pkg to "safety-fixture"))
            val legacyPlan = AppAutomation.resolve(context, pkg)
            check(legacyPlan.sendMode == InputSendMode.DELAY && legacyPlan.steps == listOf(secret, "done"))
            AppAutomation.save(prefs, oldBindings)
            val delayed = execute(prompt) {
                // No mode or prompts: an actual old preset must remain executable.
                putStringArrayListExtra("inputSteps", ArrayList(legacyPlan.steps))
                putStringArrayListExtra("sensitiveValues", ArrayList(legacyPlan.steps))
            }
            check(delayed.exitCode == 0 && delayed.status.contains("退出码 0")) { "旧版延时发送失败：${delayed.status}" }
            checks.add("Legacy preset resolves through automation bridge and sends successfully without mode/prompts")

            val timeout = install("timeout", "#!/system/bin/sh\nsleep 30\n")
            val timed = execute(timeout) { putExtra("timeoutSeconds", 5) }
            check(timed.status.contains("超过 5 秒")) { "任务未按超时终止：${timed.status}" }
            checks.add("Real task watchdog stops a sleeping process group")

            val missingPrompt = execute(prompt) {
                putExtra("inputSendMode", "prompt")
                putStringArrayListExtra("inputSteps", arrayListOf("value"))
                putStringArrayListExtra("inputPrompts", arrayListOf("UNMATCHABLE>"))
                putExtra("timeoutSeconds", 5)
            }
            check(missingPrompt.status.contains("超过 5 秒"))
            checks.add("Unmatched prompt sends nothing and task stops at deadline")

            AppAutomation.save(prefs, oldBindings + (pkg to "safety-fixture"))
            val rejected = execute(prompt) {
                putExtra("automationPackage", pkg); putExtra("automationShortcut", "safety-fixture")
            }
            check(rejected.exitCode == null)
            val rejectedReason = context.getSharedPreferences("automation_status", Context.MODE_PRIVATE).getString("message", "") ?: ""
            check(rejectedReason.contains("离开前台") || rejectedReason.contains("锁屏")) { "前台保护拒绝原因异常：$rejectedReason" }
            checks.add("Actual last-moment foreground/lock guard rejects native execution: $rejectedReason")
            return checks
        } finally {
            if (SessionState.running) {
                ExecutionService.active?.terminate()
                val deadline = SystemClock.elapsedRealtime() + 10_000
                while (SessionState.running && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
            }
            AppAutomation.save(prefs, oldBindings)
            AppAutomation.rearm(prefs, pkg)
            val editor = prefs.edit().remove(inputKey).remove(otherKey).putBoolean("saveLogs", oldLogging)
                .putString("appAutomationOptions", oldOptions).putString("quickShortcuts", oldShortcuts)
            generatedPresetKey?.let { editor.remove(it) }
            editor.commit()
            historyPrefs.edit().putString("records", oldHistory).commit()
            statusPrefs.edit().putString("message", oldStatus).putLong("time", oldStatusTime).commit()
            context.getSystemService(android.app.NotificationManager::class.java).cancel(9)
            records.forEach { token -> runCatching { root.runBounded("rm -f ${root.quote("${root.managedRoot}/logs/$token.log")}") } }
            files.forEach { runCatching { root.delete(it) } }
        }
    }
}
