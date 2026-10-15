package com.stealth.rootexecutor

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.*
import android.webkit.*
import android.widget.*
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

private data class SyncSource(val label: String, val path: String)

class MainActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private val root = RootExecutor()
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private lateinit var page: LinearLayout
    private lateinit var content: FrameLayout
    private var terminal: WebView? = null
    private var terminalReady = false
    private var terminalStatus: TextView? = null
    private var modeSelector: Button? = null
    private var granted = false
    private var checking = false
    private var tab = 0
    private var selected: ManagedFile? = null
    private var dark = true
    private var background = 0
    private var panel = 0
    private var foreground = 0
    private val accent = Color.rgb(40, 185, 207)
    private val listener: (String) -> Unit = { chunk ->
        if (terminalReady) {
            terminal?.evaluateJavascript("window.setMode(${JSONObject.quote(SessionState.mode.key)});window.feed(${JSONObject.quote(chunk)})", null)
        }
        terminalStatus?.text = SessionState.status
        modeSelector?.text = modeText()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        colors()
        setTheme(if (dark) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar)
        super.onCreate(savedInstanceState)
        showGate(); checkRoot()
    }
    private fun colors() {
        dark = when (prefs.getString("theme", "system")) {
            "dark" -> true; "light" -> false
            else -> resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }
        background = Color.parseColor(if (dark) "#080d14" else "#edf2f6")
        panel = Color.parseColor(if (dark) "#111b28" else "#ffffff")
        foreground = Color.parseColor(if (dark) "#d9e7f5" else "#182b3e")
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun text(value: String, size: Float = 15f, color: Int = foreground) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); setPadding(dp(4), dp(8), dp(4), dp(8))
    }
    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setTextColor(accent); textSize = 13f
        backgroundTintList = android.content.res.ColorStateList.valueOf(panel)
        background = InsetDrawable(GradientDrawable().apply {
            setColor(panel); cornerRadius = dp(10).toFloat()
            setStroke(dp(1), if (dark) Color.rgb(61, 83, 105) else Color.rgb(171, 192, 207))
        }, dp(3))
        setOnClickListener { action() }
    }
    private fun base(): LinearLayout {
        val result = column().apply { setPadding(dp(16), 0, dp(16), 0); setBackgroundColor(this@MainActivity.background) }
        setContentView(result)
        result.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                val ime = insets.getInsets(WindowInsets.Type.ime())
                view.setPadding(dp(16) + bars.left, bars.top, dp(16) + bars.right, maxOf(bars.bottom, ime.bottom))
            } else view.setPadding(dp(16), insets.systemWindowInsetTop, dp(16), insets.systemWindowInsetBottom)
            insets
        }
        result.requestApplyInsets()
        return result
    }
    private fun showGate(message: String = "正在检查 Root 权限…") {
        page = base().apply { gravity = Gravity.CENTER_VERTICAL }
        page.addView(text("tubing", 40f, accent).apply { typeface = Typeface.MONOSPACE })
        page.addView(text("设备授权", 23f))
        page.addView(text("连接设备的执行核心。\n授权成功后，即可进入交互终端。"))
        page.addView(text(message, 15f, accent))
        page.addView(button(if (checking) "正在检查…" else "重新授权") { checkRoot() })
        page.addView(button("退出应用") { finishAndRemoveTask() })
    }
    private fun checkRoot() {
        if (checking) return
        checking = true; showGate()
        io.execute {
            val result = try { root.initialize() } catch (e: Exception) { CommandResult(-1, e.message ?: "无法启动 su") }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                checking = false; granted = result.code == 0
                if (granted) {
                    showMain()
                    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9)
                } else showGate("未获得 Root 授权，无法进入。\n请在 Root 管理器中允许 tubing。\n退出码：${result.code}\n${result.output}")
            }
        }
    }
    private fun showMain() {
        page = base()
        val heading = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(text("tubing", 27f, accent).apply { typeface = Typeface.MONOSPACE }, LinearLayout.LayoutParams(0, dp(58), 1f))
        heading.addView(text("● 已授权", 12f, accent)); page.addView(heading)
        content = FrameLayout(this)
        page.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        val nav = LinearLayout(this)
        listOf("文件执行", "文件管理", "设置").forEachIndexed { index, label ->
            nav.addView(button(label) { tab = index; showTab() }, LinearLayout.LayoutParams(0, dp(58), 1f))
        }
        page.addView(nav); showTab()
    }
    private fun showTab() {
        if (!granted) return
        content.removeAllViews()
        val nav = page.getChildAt(page.childCount - 1) as LinearLayout
        for (i in 0..2) (nav.getChildAt(i) as Button).setTextColor(if (tab == i) accent else foreground)
        when (tab) {
            0 -> executionPage()
            1 -> content.addView(column().apply {
                gravity = Gravity.CENTER; addView(text("文件管理", 25f)); addView(text("功能开发中", 15f, accent)); addView(text("导入和选择文件请前往「文件执行」", 13f))
            }, FrameLayout.LayoutParams(-1, -1))
            else -> settingsPage()
        }
    }
    private fun executionPage() {
        val area = column()
        val actions = LinearLayout(this)
        actions.addView(button("导入文件") { AlertDialog.Builder(this).setTitle("导入文件").setItems(arrayOf("SH 脚本（系统文件选择器）", "ELF 二进制（系统文件选择器）", "从同步路径导入")) { _, i ->
            if (i == 2) chooseSyncSource() else
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }, 100 + i)
        }.show() }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(button("选择文件") { chooseFile() }, LinearLayout.LayoutParams(0, -2, 1f)); area.addView(actions)
        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        controls.addView(text(if (SessionState.running) SessionState.title else selected?.name ?: "选择文件后开始执行", 12f), LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(button("执行") { launch() })
        controls.addView(button("终止") { if (SessionState.running) AlertDialog.Builder(this).setMessage("终止当前脚本及其子进程？").setNegativeButton("取消", null).setPositiveButton("终止") { _, _ -> ExecutionService.active?.terminate() }.show() })
        area.addView(controls)
        modeSelector = button(modeText()) { chooseMode() }
        area.addView(modeSelector)
        terminalStatus = text(SessionState.status, 12f, accent); area.addView(terminalStatus)
        val web = terminal ?: createTerminal().also { terminal = it }
        (web.parent as? ViewGroup)?.removeView(web)
        area.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        val keys = LinearLayout(this)
        listOf("中断" to "\u0003", "清屏" to "", "结束输入" to "\u0004", "制表" to "\t", "退出键" to "\u001b", "↑" to "\u001b[A", "↓" to "\u001b[B", "←" to "\u001b[D", "→" to "\u001b[C", "回车" to "\r").forEach { (label, value) ->
            keys.addView(button(label) {
                if (label == "清屏") {
                    SessionState.clearOutput()
                    web.evaluateJavascript("term.clear()", null)
                } else web.evaluateJavascript("window.key(${JSONObject.quote(value)})", null)
            }.apply { isSingleLine = true }, LinearLayout.LayoutParams(dp(78), dp(46)))
        }
        area.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(keys) }); content.addView(area, FrameLayout.LayoutParams(-1, -1))
    }

    private fun syncSources(): MutableList<SyncSource> {
        val saved = prefs.getString("syncSources", null)
        if (saved != null) return try {
            val array = org.json.JSONArray(saved)
            MutableList(array.length()) { i -> array.getJSONObject(i).let { SyncSource(it.getString("label"), it.getString("path")) } }
        } catch (_: Exception) { mutableListOf() }
        return mutableListOf(
            SyncSource("QQ", "/storage/emulated/0/Android/data/com.tencent.mobileqq/Tencent/QQfile_recv/"),
            SyncSource("Telegram", "/storage/emulated/0/Android/data/org.telegram.messenger.web/files/Telegram/Telegram Files/")
        ).also { saveSyncSources(it) }
    }

    private fun saveSyncSources(sources: List<SyncSource>) {
        val array = org.json.JSONArray()
        sources.forEach { array.put(JSONObject().put("label", it.label).put("path", it.path)) }
        prefs.edit().putString("syncSources", array.toString()).apply()
    }

    private fun manageSyncSources() {
        val sources = syncSources()
        val labels = sources.map { "${it.label}  ·  ${it.path}" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("同步路径")
            .setItems(labels) { _, index -> editSyncSource(index) }
            .setPositiveButton("新增路径") { _, _ -> editSyncSource(null) }
            .setNegativeButton("关闭", null).show()
    }

    private fun chooseSyncSource() {
        val sources = syncSources()
        val labels = sources.map { "${it.label}  ·  ${it.path}" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("选择同步路径")
            .setItems(labels) { _, index -> syncSource(sources[index].path) }
            .setPositiveButton("管理路径") { _, _ -> manageSyncSources() }
            .setNegativeButton("取消", null).show()
    }

    private fun editSyncSource(index: Int?) {
        val sources = syncSources()
        val current = index?.let { sources.getOrNull(it) }
        val form = column().apply { setPadding(dp(20), dp(8), dp(20), 0) }
        val labelInput = EditText(this).apply { hint = "路径标签，例如：我的下载"; setText(current?.label ?: "") }
        val pathInput = EditText(this).apply {
            hint = "绝对路径，例如：/storage/emulated/0/Download"; setText(current?.path ?: "")
            setSingleLine(false); minLines = 2; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        form.addView(labelInput); form.addView(pathInput)
        val dialog = AlertDialog.Builder(this).setTitle(if (current == null) "新增同步路径" else "编辑同步路径")
            .setView(form).setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .apply { if (current != null) setNeutralButton("删除", null) }.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val label = labelInput.text.toString().trim()
                val path = pathInput.text.toString().trim()
                if (label.isBlank() || path.isBlank() || !path.startsWith('/')) {
                    message("路径无效", "请填写标签和以 / 开头的绝对路径。")
                    return@setOnClickListener
                }
                val updated = syncSources()
                val entry = SyncSource(label, path)
                if (index == null || current == null) updated.add(entry) else updated[index] = entry
                saveSyncSources(updated); dialog.dismiss(); showTab()
            }
            if (current != null) dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                AlertDialog.Builder(this).setMessage("删除同步路径“${current.label}”？")
                    .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
                        val updated = syncSources(); if (index in updated.indices) updated.removeAt(index!!)
                        saveSyncSources(updated); dialog.dismiss(); showTab()
                    }.show()
            }
        }
        dialog.show()
    }

    private fun syncSource(path: String) {
        work({
            val (result, entries) = root.listSource(path)
            check(result.code == 0) { result.output.ifBlank { "无法读取目录" } }
            entries
        }) { entries -> showSourceEntries(path, entries) }
    }

    private fun showSourceEntries(path: String, entries: List<SourceEntry>) {
        if (entries.isEmpty()) { message("目录为空", path); return }
        val labels = entries.map { entry ->
            val name = entry.path.substringAfterLast('/')
            if (entry.directory) "📁  $name" else "📄  $name"
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle(path).setItems(labels) { _, index ->
            val entry = entries[index]
            if (entry.directory) syncSource(entry.path)
            else chooseSourceKind(entry.path)
        }.setNegativeButton("关闭", null).show()
    }

    private fun chooseSourceKind(path: String) {
        val name = path.substringAfterLast('/')
        val options = if (name.endsWith(".sh", true)) arrayOf("导入为 SH 脚本") else arrayOf("导入为 ELF 二进制", "导入为 SH 脚本")
        AlertDialog.Builder(this).setTitle(name).setItems(options) { _, index ->
            val kind = if (options.size == 1 || index == 1) "scripts" else "binaries"
            work({
                val result = root.importSource(path, name, kind)
                check(result.code == 0) { result.output.ifBlank { "导入失败" } }
                result
            }) { result ->
                val fields = result.output.lineSequence().firstOrNull { it.startsWith("IMPORTED|") }?.split('|')
                if (fields?.size == 3) selected = ManagedFile(fields[1], fields[2], "777")
                tab = 0; showTab(); message("导入完成", selected?.name ?: "文件已导入")
            }
        }.setNegativeButton("取消", null).show()
    }
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private fun createTerminal() = WebView(this).apply {
        setBackgroundColor(if (dark) Color.parseColor("#0a1018") else Color.parseColor("#f2f5f8"))
        settings.javaScriptEnabled = true; settings.allowFileAccess = true; settings.allowContentAccess = false; settings.setSupportZoom(false)
        webViewClient = object : WebViewClient() { override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true }
        addJavascriptInterface(object {
            @JavascriptInterface fun send(value: String) { ExecutionService.active?.send(value) }
            @JavascriptInterface fun resize(cols: Int, rows: Int) { SessionState.cols = cols; SessionState.rows = rows; ExecutionService.active?.resize(cols, rows) }
            @JavascriptInterface fun ready() { runOnUiThread {
                if (!isDestroyed) { terminalReady = true; evaluateJavascript("window.setSize(${prefs.getInt("fontSize", 14)})", null); SessionState.subscribe(listener) }
            } }
        }, "Android")
        loadUrl("file:///android_asset/terminal/index.html?${if (dark) "dark" else "light"}")
    }
    private fun chooseFile() = work({ root.list().let { (r, files) -> check(r.code == 0) { r.output }; files } }) { files ->
        if (files.isEmpty()) message("暂无文件", "请先导入 SH 或 ELF 文件。")
        else AlertDialog.Builder(this).setTitle("选择执行文件").setItems(files.map { "${it.name}  ·  ${it.mode}" }.toTypedArray()) { _, i -> selected = files[i]; showTab() }.show()
    }
    private fun launch() {
        if (SessionState.running) { message("脚本正在运行", "请先结束当前会话。"); return }
        val file = selected ?: run { chooseFile(); return }
        SessionState.clear(); terminal?.evaluateJavascript("term.reset()", null)
        val mode = preferredMode()
        SessionState.mode = mode
        terminal?.evaluateJavascript("window.setMode(${JSONObject.quote(mode.key)})", null)
        startForegroundService(Intent(this, ExecutionService::class.java).putExtra("kind", file.kind).putExtra("name", file.name).putExtra("mode", mode.key))
    }
    private fun preferredMode() = ExecutionMode.fromKey(prefs.getString("executionMode", "pipe"))
    private fun modeText(): String {
        val next = preferredMode()
        return if (SessionState.running && SessionState.mode != next) "当前：${SessionState.mode.label} · 下次：${next.label}"
        else "执行方式 · ${if (SessionState.running) SessionState.mode.label else next.label}"
    }
    private fun chooseMode() {
        val modes = ExecutionMode.entries
        AlertDialog.Builder(this).setTitle(if (SessionState.running) "执行方式（下次执行生效）" else "选择执行方式")
            .setSingleChoiceItems(arrayOf("管道（默认）· 普通问答、批处理", "PTY 伪终端 · 终端菜单、方向键交互"), modes.indexOf(preferredMode())) { dialog, index ->
                prefs.edit().putString("executionMode", modes[index].key).apply()
                dialog.dismiss(); showTab()
            }.setNegativeButton("取消", null).show()
    }
    private fun settingsPage() {
        val area = column(); area.addView(text("设置", 25f)); area.addView(text("显示与终端", 13f, accent))
        modeSelector = button(modeText()) { chooseMode() }
        area.addView(modeSelector)
        area.addView(button("主题 · " + when (prefs.getString("theme", "system")) { "dark" -> "深色"; "light" -> "浅色"; else -> "跟随系统" }) {
            AlertDialog.Builder(this).setTitle("主题").setItems(arrayOf("跟随系统", "深色", "浅色")) { _, i ->
                prefs.edit().putString("theme", arrayOf("system", "dark", "light")[i]).apply(); recreate()
            }.show()
        })
        area.addView(button("终端字体 · ${prefs.getInt("fontSize", 14)}") {
            val sizes = arrayOf(12, 14, 16, 18, 20)
            AlertDialog.Builder(this).setTitle("终端字体大小").setItems(sizes.map { "$it" }.toTypedArray()) { _, i ->
                prefs.edit().putInt("fontSize", sizes[i]).apply(); terminal?.evaluateJavascript("window.setSize(${sizes[i]})", null); showTab()
            }.show()
        })
        area.addView(Switch(this).apply {
            text = "执行结束后自动保存日志"; setTextColor(this@MainActivity.foreground); isChecked = prefs.getBoolean("saveLogs", true)
            setPadding(dp(10), dp(16), dp(10), dp(16)); setOnCheckedChangeListener { _, enabled -> prefs.edit().putBoolean("saveLogs", enabled).apply() }
        })
        area.addView(text("设备与记录", 13f, accent)); area.addView(text("Root 状态 · 已授权"))
        area.addView(button("重新检查 Root") { checkRoot() })
        area.addView(button("查看执行日志") { work({ root.logs() }) { message("执行日志", it.output.ifBlank { "暂无日志" }) } })
        area.addView(button("清理临时文件") {
            if (SessionState.running) message("暂时无法清理", "请在执行结束后清理临时文件。")
            else work({ root.cleanTmp() }) { message("清理临时文件", if (it.code == 0) "清理完成" else it.output) }
        })
        area.addView(text("tubing ${packageManager.getPackageInfo(packageName, 0).versionName}\nRoot 交互终端", 12f))
        content.addView(ScrollView(this).apply { addView(area) }, FrameLayout.LayoutParams(-1, -1))
    }
    @Deprecated("SAF result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode !in 100..101 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val kind = if (requestCode == 100) "scripts" else "binaries"
        work({
            val name = displayName(uri); val temp = File.createTempFile("incoming-", ".tmp", cacheDir)
            try {
                (contentResolver.openInputStream(uri) ?: error("无法读取文件")).use { input -> temp.outputStream().use { input.copyTo(it) } }
                if (kind == "scripts") require(name.endsWith(".sh", true)) { "请选择 .sh 脚本" }
                else temp.inputStream().use { input -> val magic = ByteArray(4); require(input.read(magic) == 4 && magic.contentEquals(byteArrayOf(127, 69, 76, 70))) { "文件不是 ELF 二进制" } }
                root.import(temp, name, kind).also { check(it.code == 0) { it.output } }
            } finally { temp.delete() }
        }) { result ->
            val fields = result.output.lineSequence().firstOrNull { it.startsWith("IMPORTED|") }?.split('|')
            if (fields?.size == 3) selected = ManagedFile(fields[1], fields[2], "777")
            tab = 0; showTab(); message("导入完成", selected?.name ?: "文件已导入")
        }
    }
    private fun displayName(uri: Uri): String = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "未命名"
    private fun message(title: String, body: String) { if (!isDestroyed) AlertDialog.Builder(this).setTitle(title).setMessage(body).setPositiveButton("确定", null).show() }
    private fun <T> work(task: () -> T, done: (T) -> Unit) { io.execute { try { val value = task(); runOnUiThread { if (!isDestroyed) done(value) } } catch (e: Exception) { runOnUiThread { message("操作失败", e.message ?: "未知错误") } } } }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); if (prefs.getString("theme", "system") == "system") recreate() }
    override fun onBackPressed() { if (granted) moveTaskToBack(true) else super.onBackPressed() }
    override fun onDestroy() { SessionState.unsubscribe(listener); terminalReady = false; terminal?.destroy(); io.shutdown(); super.onDestroy() }
}
