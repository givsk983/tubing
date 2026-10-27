package com.stealth.rootexecutor

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import org.json.JSONObject

internal data class AutomationPlan(val file: ManagedFile, val steps: List<String>, val prompts: List<String>, val sensitive: Boolean, val shortcut: String,
    val sendMode: InputSendMode = InputSendMode.DELAY)

/** Only package names leave app-private storage; scripts and inputs stay in Tubing. */
internal object AppAutomation {
    private const val KEY = "appAutomationBindings"
    private const val OPTIONS = "appAutomationOptions"
    fun validPackage(value: String) = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+").matches(value)

    fun bindings(prefs: SharedPreferences): Map<String, String> = runCatching {
        val json = JSONObject(prefs.getString(KEY, "{}") ?: "{}")
        json.keys().asSequence().filter(::validPackage).associateWith { json.getString(it) }
    }.getOrDefault(emptyMap())

    fun save(prefs: SharedPreferences, bindings: Map<String, String>) {
        val previous = bindings(prefs)
        val editor = prefs.edit().putString(KEY, JSONObject(bindings).toString())
        (previous.keys + bindings.keys).filter { previous[it] != bindings[it] }.forEach {
            editor.remove("automationLast_$it").remove("automationConsumed_$it")
        }
        check(editor.commit()) { "保存绑定失败" }
    }

    fun policy(prefs: SharedPreferences, pkg: String): AutomationPolicy {
        val item = runCatching { JSONObject(prefs.getString(OPTIONS, "{}") ?: "{}").optJSONObject(pkg) }.getOrNull()
        return AutomationPolicy(item?.optInt("cooldown", 60)?.coerceIn(10, 86400) ?: 60,
            item?.optInt("timeout", 120)?.coerceIn(5, 3600) ?: 120, item?.optBoolean("once", false) ?: false)
    }
    fun savePolicy(prefs: SharedPreferences, pkg: String, policy: AutomationPolicy) {
        val json = JSONObject(prefs.getString(OPTIONS, "{}") ?: "{}")
        json.put(pkg, JSONObject().put("cooldown", policy.cooldownSeconds).put("timeout", policy.timeoutSeconds).put("once", policy.once))
        check(prefs.edit().putString(OPTIONS, json.toString()).commit())
    }
    fun rearm(prefs: SharedPreferences, pkg: String) { prefs.edit().remove("automationLast_$pkg").remove("automationConsumed_$pkg").commit() }
    fun blocked(prefs: SharedPreferences, pkg: String): String? = policy(prefs, pkg).blockedReason(System.currentTimeMillis(),
        prefs.getLong("automationLast_$pkg", 0), prefs.getBoolean("automationConsumed_$pkg", false))
    fun markAttempt(prefs: SharedPreferences, pkg: String) {
        check(prefs.edit().putLong("automationLast_$pkg", System.currentTimeMillis()).putBoolean("automationConsumed_$pkg", true).commit())
    }
    fun status(context: Context, message: String, failed: Boolean = false) {
        context.getSharedPreferences("automation_status", Context.MODE_PRIVATE).edit()
            .putString("message", message).putLong("time", System.currentTimeMillis()).apply()
        if (failed) {
            val manager = context.getSystemService(android.app.NotificationManager::class.java)
            manager.createNotificationChannel(android.app.NotificationChannel("automation_errors", "自动执行异常", android.app.NotificationManager.IMPORTANCE_DEFAULT))
            val open = android.app.PendingIntent.getActivity(context, 3, android.content.Intent(context, MainActivity::class.java), android.app.PendingIntent.FLAG_IMMUTABLE)
            runCatching { manager.notify(9, android.app.Notification.Builder(context, "automation_errors")
                .setSmallIcon(android.R.drawable.stat_notify_error).setContentTitle("Tubing · 自动执行未完成")
                .setContentText(message).setContentIntent(open).setAutoCancel(true).build()) }
        }
    }
    fun requireForeground(context: Context, pkg: String, shortcut: String, targetUser: Int = 0) {
        check(context.getSystemService(android.os.PowerManager::class.java).isInteractive &&
            !context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) { "已熄屏或锁屏，取消启动" }
        check(bindings(context.getSharedPreferences("settings", Context.MODE_PRIVATE))[pkg] == shortcut) { "绑定已变更，取消启动" }
        val result = RootExecutor().runBounded("/system/bin/am get-current-user </dev/null && /system/bin/dumpsys -t 3 activity activities </dev/null", 5)
        val foreground = ForegroundParser.identity(result.output)
        check(result.code == 0 && result.output.lineSequence().firstOrNull()?.trim() == "0" &&
            foreground?.packageName == pkg && foreground.userId == targetUser) { "目标 App 或分身已离开前台或无法确认，取消启动" }
        // Querying the system can take time: recheck the lock after the query too.
        check(context.getSystemService(android.os.PowerManager::class.java).isInteractive &&
            !context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) { "已熄屏或锁屏，取消启动" }
    }

    fun moduleStatus(root: RootExecutor): String {
        val result = root.runBounded("""
            d=/data/adb/modules/tubing_app_trigger
            [ -d "${'$'}d" ] || { echo '模块未安装'; exit 0; }
            [ ! -e "${'$'}d/disable" ] || { echo '模块已禁用'; exit 0; }
            [ ! -e "${'$'}d/remove" ] || { echo '模块待卸载'; exit 0; }
            [ -f "${'$'}d/heartbeat" ] || { echo '未发现运行心跳，请重启模块'; exit 0; }
            now=${'$'}(cut -d. -f1 /proc/uptime)
            beat=${'$'}(cat "${'$'}d/heartbeat")
            case "${'$'}beat" in ''|*[!0-9]*) echo '模块心跳无效'; exit 0 ;; esac
            age=${'$'}((now-beat))
            if [ "${'$'}age" -lt 0 ] || [ "${'$'}age" -gt 40 ]; then echo '监测已停止或无响应'; else echo '监测在线'; fi
            tail -n 1 "${'$'}d/status.log" 2>/dev/null
            tail -n 4 "${'$'}d/last-trigger.log" 2>/dev/null || true
        """.trimIndent(), 5)
        if (result.code != 0) return "读取状态失败：Root 查询超时或授权不可用"
        val lines = result.output.trim().lines()
        val state = lines.getOrNull(1) ?: ""
        val label = when {
            state.startsWith("Waiting for bindings") -> "等待绑定同步，请点击重新同步"
            state.startsWith("Monitoring:") -> "等待绑定应用进入前台"
            state.startsWith("Confirming bound") -> "正在确认目标应用"
            state.contains("screen asleep") -> "熄屏中，暂停监测"
            state.contains("screen locked") -> "锁屏或锁屏状态未知，暂停执行"
            state.contains("user query") -> "用户状态不符或查询失败，稍后重试"
            state.contains("backing off") -> "系统状态查询失败，正在降低频率重试"
            state.contains("Blocked by AutoLaunch") -> "系统禁止自启动，请在系统设置中允许 Tubing 自启动"
            state.startsWith("Dispatch failed") -> "启动请求失败或超时，请检查系统后台限制"
            state.startsWith("Dispatching") -> "正在发送启动请求"
            state.startsWith("Request dispatched") -> "启动请求已发出，执行结果见下方"
            state.startsWith("Monitor stopped") -> "监测已停止"
            else -> ""
        }
        return listOfNotNull(lines.firstOrNull(), label.takeIf { it.isNotEmpty() }).joinToString("\n")
    }

    fun sync(root: RootExecutor, bindings: Map<String, String>, ownerPackage: String = "com.stealth.rootexecutor"): CommandResult {
        require(bindings.keys.all(::validPackage))
        require(validPackage(ownerPackage))
        val packages = if (bindings.isEmpty()) "" else bindings.keys.sorted().joinToString("\n", postfix = "\n")
        return root.runBounded("""
            set -eu
            d=/data/adb/tubing-automation
            [ ! -L "${'$'}d" ]
            mkdir -p "${'$'}d"
            chmod 700 "${'$'}d"
            umask 077
            owner=${'$'}(mktemp "${'$'}d/owner.XXXXXX")
            printf %s ${root.quote(ownerPackage)} > "${'$'}owner"
            mv -f "${'$'}owner" "${'$'}d/owner"
            t=${'$'}(mktemp "${'$'}d/packages.XXXXXX")
            printf %s ${root.quote(packages)} > "${'$'}t"
            mv -f "${'$'}t" "${'$'}d/packages"
        """.trimIndent())
    }

    fun resolve(context: Context, packageName: String): AutomationPlan {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val name = bindings(prefs)[packageName] ?: error("App 未绑定快捷方式")
        val shortcuts = org.json.JSONArray(prefs.getString("quickShortcuts", "[]"))
        val shortcut = (0 until shortcuts.length()).map { shortcuts.getJSONObject(it) }
            .firstOrNull { it.getString("name") == name } ?: error("快捷方式已删除：$name")
        val file = ManagedFile(shortcut.getString("kind"), shortcut.getString("fileName"), "")
        val root = RootExecutor()
        val path = root.quote(root.filePath(file))
        val result = root.runBounded("[ -f $path ] && [ ! -L $path ]", 5)
        check(result.code == 0) { "脚本不存在、Root 不可用或检查超时" }
        val presetName = shortcut.optString("presetName")
        if (presetName.isBlank()) return AutomationPlan(file, emptyList(), emptyList(), false, name)
        val encoded = Base64.encodeToString("${file.kind}/${file.name}".toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val presets = org.json.JSONArray(PrivateInputStore.read(prefs, "scriptInput_presets_$encoded"))
        val preset = (0 until presets.length()).map { presets.getJSONObject(it) }
            .firstOrNull { it.getString("name") == presetName } ?: error("输入方案已删除：$presetName")
        val steps = preset.getJSONArray("steps")
        val prompts = preset.optJSONArray("prompts")
        val values = List(steps.length()) { steps.getString(it) }
        val expected = List(steps.length()) { prompts?.optString(it) ?: "" }
        val sendMode = InputSendMode.fromKey(preset.optString("sendMode"))
        check(sendMode.valid(values, expected)) {
            "输入方案配置不完整，请补齐提示词或改用延时发送"
        }
        return AutomationPlan(file, values, expected, preset.optBoolean("sensitive", false), name, sendMode)
    }
}
