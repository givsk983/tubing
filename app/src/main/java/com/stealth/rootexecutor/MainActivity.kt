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
import android.util.Base64
import android.view.*
import android.webkit.*
import android.widget.*
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

private data class SyncSource(val label: String, val path: String)
private data class ScriptInputPreset(val name: String, val steps: List<String>)
private data class QuickShortcut(val name: String, val kind: String, val fileName: String, val presetName: String?)

class MainActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private val root = RootExecutor()
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private lateinit var page: LinearLayout
    private lateinit var content: FrameLayout
    private var terminal: WebView? = null
    private var terminalReady = false
    private var terminalStatus: TextView? = null
    private var startButton: Button? = null
    private var shortcutButton: Button? = null
    private var interfaceModeBar: LinearLayout? = null
    private var interfaceModeButton: Button? = null
    private var executionKeyBar: FrameLayout? = null
    private var inputHistoryButton: Button? = null
    private var inputMemoryButton: Button? = null
    private var modeSelector: Button? = null
    private var granted = false
    private var checking = false
    private var tab = 0
    private var selected: ManagedFile? = null
    private var inputHistoryFile: ManagedFile? = null
    private var trackInputHistoryForSession = true
    private val inputCaptureLock = Any()
    private val inputCaptureBuffer = StringBuilder()
    private var skipNextLineFeed = false
    private var skipEscapeSequence = 0
    private var inputCaptureTrackable = true
    private var dark = true
    private var background = 0
    private var panel = 0
    private var foreground = 0
    private var leftPath = root.managedRoot
    private var rightPath = "/storage/emulated/0"
    private val leftBrowserHistory = mutableListOf<String>()
    private val rightBrowserHistory = mutableListOf<String>()
    private var lastBackPressedAt = 0L
    private var leftEntries: List<BrowserEntry> = emptyList()
    private var rightEntries: List<BrowserEntry> = emptyList()
    private var selectedLeft: BrowserEntry? = null
    private var selectedRight: BrowserEntry? = null
    private var selectedLeftRow: View? = null
    private var selectedRightRow: View? = null
    private var activeBrowserLeft = true
    private var browserTargetLabel: TextView? = null
    private var leftBrowserCaption: TextView? = null
    private var rightBrowserCaption: TextView? = null
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
                view.setPadding(dp(16) + bars.left, bars.top, dp(16) + bars.right, bars.bottom)
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
        if (!prefs.contains("homeDefaultQuickInterface")) {
            prefs.edit().putBoolean("homeDefaultQuickInterface", prefs.getBoolean("quickInterface", false)).apply()
        }
        prefs.edit().putBoolean("quickInterface", prefs.getBoolean("homeDefaultQuickInterface", false)).apply()
        page = base()
        val heading = FrameLayout(this)
        heading.addView(text("tubing", 27f, accent).apply { typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER_VERTICAL },
            FrameLayout.LayoutParams(-2, dp(58), Gravity.START or Gravity.CENTER_VERTICAL))
        startButton = createStartButton()
        heading.addView(startButton, FrameLayout.LayoutParams(-2, dp(48), Gravity.CENTER))
        shortcutButton = button(shortcutButtonLabel()) { showQuickShortcuts() }
        heading.addView(shortcutButton, FrameLayout.LayoutParams(-2, dp(48), Gravity.END or Gravity.CENTER_VERTICAL))
        page.addView(heading)
        content = FrameLayout(this)
        page.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        interfaceModeButton = button(interfaceModeLabel()) { chooseInterfaceMode() }.apply {
            textSize = 12f
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(12), 0, dp(12), 0)
            backgroundTintList = null
            background = InsetDrawable(GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), accent)
            }, dp(3))
        }
        interfaceModeBar = LinearLayout(this).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            addView(interfaceModeButton, LinearLayout.LayoutParams(-2, dp(36)))
        }
        page.addView(interfaceModeBar, LinearLayout.LayoutParams(-1, dp(44)))
        executionKeyBar = FrameLayout(this)
        page.addView(executionKeyBar, LinearLayout.LayoutParams(-1, dp(46)))
        val nav = LinearLayout(this)
        listOf("文件执行", "文件管理", "设置").forEachIndexed { index, label ->
            nav.addView(button(label) { tab = index; showTab() }, LinearLayout.LayoutParams(0, dp(58), 1f))
        }
        page.addView(nav); showTab()
    }
    private fun showTab() {
        if (!granted) return
        val quick = prefs.getBoolean("quickInterface", false)
        if (quick) tab = 0
        content.removeAllViews()
        val nav = page.getChildAt(page.childCount - 1) as LinearLayout
        nav.visibility = if (quick) View.INVISIBLE else View.VISIBLE
        executionKeyBar?.removeAllViews()
        executionKeyBar?.visibility = if (tab != 0) View.GONE else if (quick) View.INVISIBLE else View.VISIBLE
        interfaceModeBar?.visibility = if (tab == 0) View.VISIBLE else View.GONE
        interfaceModeButton?.text = interfaceModeLabel()
        val heading = page.getChildAt(0) as FrameLayout
        heading.visibility = if (tab == 1 || quick) View.GONE else View.VISIBLE
        startButton = heading.getChildAt(1) as Button
        shortcutButton = heading.getChildAt(2) as Button
        updateShortcutButton()
        startButton?.visibility = if (tab == 0) View.VISIBLE else View.GONE
        shortcutButton?.visibility = if (tab == 0) View.VISIBLE else View.GONE
        for (i in 0..2) (nav.getChildAt(i) as Button).setTextColor(if (tab == i) accent else foreground)
        when (tab) {
            0 -> if (!quick) executionPage() else {
                terminalStatus = null
                modeSelector = null
                inputHistoryButton = null
                inputMemoryButton = null
                quickExecutionPage()
            }
            1 -> { fileManagerPage(); refreshBrowsers() }
            else -> settingsPage()
        }
    }

    private fun createStartButton(large: Boolean = false) = button("Start") { startQuickShortcut() }.apply {
        setTextColor(Color.rgb(8, 25, 32))
        textSize = if (large) 26f else 18f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(22), 0, dp(22), 0)
        backgroundTintList = null
        background = InsetDrawable(GradientDrawable().apply {
            setColor(accent)
            cornerRadius = dp(if (large) 22 else 12).toFloat()
        }, dp(3))
    }

    private fun quickExecutionPage() {
        val group = column().apply { gravity = Gravity.CENTER_HORIZONTAL }
        group.addView(text("tubing", 40f, accent).apply {
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            letterSpacing = 0.06f
        }, LinearLayout.LayoutParams(-1, dp(76)))
        shortcutButton = button(shortcutButtonLabel()) { showQuickShortcuts() }.apply {
            textSize = 16f
            maxLines = 2
        }
        group.addView(shortcutButton, LinearLayout.LayoutParams(dp(220), dp(56)).apply { topMargin = dp(22) })
        startButton = createStartButton(true)
        group.addView(startButton, LinearLayout.LayoutParams(dp(220), dp(80)).apply { topMargin = dp(20) })
        content.addView(group, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply {
            leftMargin = dp(24)
            rightMargin = dp(24)
        })
    }

    private fun interfaceModeLabel() = if (prefs.getBoolean("quickInterface", false)) "快捷版 ▾" else "完整版 ▾"

    private fun chooseInterfaceMode() {
        val quick = prefs.getBoolean("quickInterface", false)
        AlertDialog.Builder(this).setTitle("切换界面模式")
            .setSingleChoiceItems(arrayOf("完整版", "快捷版"), if (quick) 1 else 0) { dialog, index ->
                prefs.edit().putBoolean("quickInterface", index == 1).apply()
                dialog.dismiss()
                showTab()
            }.setNegativeButton("取消", null).show()
    }

    private fun fileManagerPage() {
        content.removeAllViews()
        selectedLeftRow = null
        selectedRightRow = null
        val area = column()
        val title = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        title.addView(text("文件管理", 21f), LinearLayout.LayoutParams(0, dp(48), 1f))
        title.addView(button("刷新") { refreshBrowsers() })
        area.addView(title)
        val transfer = LinearLayout(this).apply { gravity = Gravity.CENTER }
        transfer.addView(button("复制到左侧") { copyBetween(false) }, LinearLayout.LayoutParams(0, dp(46), 1f))
        transfer.addView(button("复制到右侧") { copyBetween(true) }, LinearLayout.LayoutParams(0, dp(46), 1f))
        area.addView(transfer)
        val panes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        leftBrowserCaption = null
        rightBrowserCaption = null
        browserTargetLabel = null
        panes.addView(browserPane(true), LinearLayout.LayoutParams(0, -1, 1f))
        val divider = View(this).apply { setBackgroundColor(if (dark) Color.rgb(47, 64, 82) else Color.rgb(204, 216, 225)) }
        panes.addView(divider, LinearLayout.LayoutParams(dp(1), -1))
        panes.addView(browserPane(false), LinearLayout.LayoutParams(0, -1, 1f))
        area.addView(panes, LinearLayout.LayoutParams(-1, 0, 1f))
        browserTargetLabel = text("操作目标：${if (activeBrowserLeft) "左侧 · 已导入" else "右侧 · 设备目录"}", 11f, accent).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        area.addView(browserTargetLabel, LinearLayout.LayoutParams(-1, dp(34)))
        val navigation = object : LinearLayout(this) {
            private var gestureStartY = 0f
            private var gestureStartX = 0f
            private var openingFavorites = false

            init { gravity = Gravity.CENTER }

            override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { gestureStartY = event.y; gestureStartX = event.x; openingFavorites = false }
                    MotionEvent.ACTION_MOVE -> {
                        val deltaY = event.y - gestureStartY
                        if (!openingFavorites && -deltaY > dp(28) && kotlin.math.abs(deltaY) > kotlin.math.abs(event.x - gestureStartX)) {
                            openingFavorites = true
                            return true
                        }
                    }
                }
                return super.onInterceptTouchEvent(event)
            }

            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (openingFavorites) {
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        openingFavorites = false
                        openFavorites()
                    } else if (event.actionMasked == MotionEvent.ACTION_CANCEL) openingFavorites = false
                    return true
                }
                return super.onTouchEvent(event)
            }
        }
        navigation.addView(button("↑ 上级") { goParent(activeBrowserLeft) }, LinearLayout.LayoutParams(0, dp(46), 1f))
        navigation.addView(button("⌂ 主页") { navigateBrowser(activeBrowserLeft, if (activeBrowserLeft) root.managedRoot else "/storage/emulated/0") }, LinearLayout.LayoutParams(0, dp(46), 1f))
        navigation.addView(button("＋ 新建") { createBrowserDirectory(activeBrowserLeft) }, LinearLayout.LayoutParams(0, dp(46), 1f))
        area.addView(navigation)
        content.addView(area, FrameLayout.LayoutParams(-1, -1))
    }

    private fun browserPane(left: Boolean): LinearLayout {
        val pane = column().apply {
            setPadding(dp(3), 0, dp(3), 0)
            isClickable = true
            setOnClickListener { focusBrowser(left); clearBrowserSelection() }
        }
        val caption = text(if (left) "已导入" else "设备目录", 13f, if (activeBrowserLeft == left) accent else foreground)
        caption.setOnClickListener { focusBrowser(left) }
        if (left) leftBrowserCaption = caption else rightBrowserCaption = caption
        pane.addView(caption)
        val pathLabel = text(if (left) leftPath else rightPath, 10f).apply {
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setOnClickListener { focusBrowser(left); editBrowserPath(left) }
        }
        pane.addView(pathLabel, LinearLayout.LayoutParams(-1, dp(42)))
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            setOnClickListener {
                focusBrowser(left)
                clearBrowserSelection()
            }
        }
        val entries = if (left) leftEntries else rightEntries
        val filtered = if (left && (leftPath == root.managedRoot || leftPath == root.managedRoot + "/"))
            entries.filter { it.name == "scripts" || it.name == "binaries" } else entries
        if (filtered.isEmpty()) list.addView(text("目录为空", 12f, foreground))
        filtered.forEach { entry ->
            val isSelected = if (left) selectedLeft?.path == entry.path else selectedRight?.path == entry.path
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(7), dp(3), dp(5), dp(3))
                setBackgroundColor(if (isSelected) (if (dark) Color.rgb(25, 64, 80) else Color.rgb(210, 239, 243)) else Color.TRANSPARENT)
            }
            if (isSelected) {
                if (left) selectedLeftRow = row else selectedRightRow = row
            }
            val icon = when (entry.type) { "d" -> "📁"; "l" -> "↪"; "f" -> "📄"; else -> "▪" }
            row.addView(text("$icon  ${entry.name}", 12f, foreground).apply {
                maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE; setPadding(0, dp(3), 0, dp(1))
            })
            row.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) focusBrowser(left)
                false
            }
            row.setOnClickListener {
                activeBrowserLeft = left
                if (entry.type == "d") navigateBrowser(left, entry.path, rememberCurrent = true)
                else {
                    if (left) {
                        selectedLeftRow?.setBackgroundColor(Color.TRANSPARENT)
                        selectedLeft = entry; selectedLeftRow = row
                    } else {
                        selectedRightRow?.setBackgroundColor(Color.TRANSPARENT)
                        selectedRight = entry; selectedRightRow = row
                    }
                    row.setBackgroundColor(if (dark) Color.rgb(25, 64, 80) else Color.rgb(210, 239, 243))
                }
            }
            row.setOnLongClickListener { focusBrowser(left); showEntryActions(left, entry); true }
            list.addView(row)
            list.addView(View(this).apply { setBackgroundColor(if (dark) Color.rgb(34, 47, 62) else Color.rgb(226, 233, 238)) }, LinearLayout.LayoutParams(-1, dp(1)))
        }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(list) }
        scroll.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) focusBrowser(left)
            false
        }
        pane.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return pane
    }

    private fun focusBrowser(left: Boolean) {
        lastBackPressedAt = 0L
        activeBrowserLeft = left
        browserTargetLabel?.text = "操作目标：${if (left) "左侧 · 已导入" else "右侧 · 设备目录"}"
        leftBrowserCaption?.setTextColor(if (left) accent else foreground)
        rightBrowserCaption?.setTextColor(if (left) foreground else accent)
    }

    private fun clearBrowserSelection() {
        selectedLeftRow?.setBackgroundColor(Color.TRANSPARENT)
        selectedRightRow?.setBackgroundColor(Color.TRANSPARENT)
        selectedLeftRow = null; selectedRightRow = null
        selectedLeft = null; selectedRight = null
    }

    private fun refreshBrowsers() {
        if (!granted || tab != 1) return
        val left = leftPath; val right = rightPath
        io.execute {
            val leftResult = try { root.browse(left) } catch (e: Exception) { CommandResult(-1, e.message ?: "读取失败") to emptyList() }
            val rightResult = try { root.browse(right) } catch (e: Exception) { CommandResult(-1, e.message ?: "读取失败") to emptyList() }
            runOnUiThread {
                if (isDestroyed || tab != 1) return@runOnUiThread
                leftEntries = leftResult.second; rightEntries = rightResult.second
                if (leftResult.first.code != 0) message("左侧目录无法读取", leftResult.first.output.ifBlank { "请检查目录路径和访问权限" })
                if (rightResult.first.code != 0) message("右侧目录无法读取", rightResult.first.output.ifBlank { "请检查目录路径和访问权限" })
                fileManagerPage()
            }
        }
    }

    private fun navigateBrowser(left: Boolean, path: String, rememberCurrent: Boolean = false) {
        lastBackPressedAt = 0L
        val history = if (left) leftBrowserHistory else rightBrowserHistory
        if (rememberCurrent) history.add(if (left) leftPath else rightPath) else history.clear()
        if (left) { leftPath = path; selectedLeft = null } else { rightPath = path; selectedRight = null }
        refreshBrowsers()
    }

    private fun backBrowserDirectory(): Boolean {
        val history = if (activeBrowserLeft) leftBrowserHistory else rightBrowserHistory
        if (history.isEmpty()) return false
        lastBackPressedAt = 0L
        val previous = history.removeAt(history.lastIndex)
        if (activeBrowserLeft) { leftPath = previous; selectedLeft = null }
        else { rightPath = previous; selectedRight = null }
        refreshBrowsers()
        return true
    }

    private fun goParent(left: Boolean) {
        val path = if (left) leftPath else rightPath
        val parent = path.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" }
        navigateBrowser(left, parent)
    }

    private fun editBrowserPath(left: Boolean) {
        val input = EditText(this).apply { setSingleLine(false); setText(if (left) leftPath else rightPath); setSelection(text.length) }
        AlertDialog.Builder(this).setTitle("打开目录").setView(input).setNegativeButton("取消", null)
            .setPositiveButton("打开") { _, _ -> navigateBrowser(left, input.text.toString().trim()) }.show()
    }

    private fun createBrowserDirectory(left: Boolean) {
        val input = EditText(this).apply { hint = "文件夹名称" }
        AlertDialog.Builder(this).setTitle("新建文件夹").setView(input).setNegativeButton("取消", null)
            .setPositiveButton("创建") { _, _ ->
                val path = if (left) leftPath else rightPath
                work({ root.makeDirectory(path, input.text.toString().trim()).also { check(it.code == 0) { it.output.ifBlank { "创建失败" } } } }) { refreshBrowsers() }
            }.show()
    }

    private fun copyBetween(fromLeft: Boolean) {
        activeBrowserLeft = fromLeft
        val source = if (fromLeft) selectedLeft else selectedRight
        if (source == null) { message("请选择文件", "先在一侧点选文件，再复制到另一侧。目录可长按后复制。"); return }
        val destination = if (fromLeft) rightPath else leftPath
        work({ root.copyPath(source.path, destination).also { check(it.code == 0) { it.output.ifBlank { "复制失败" } } } }) {
            if (fromLeft) selectedLeft = null else selectedRight = null
            refreshBrowsers()
        }
    }

    private fun showEntryActions(left: Boolean, entry: BrowserEntry) {
        val actions = arrayOf("复制到另一侧", "重命名", "删除")
        AlertDialog.Builder(this).setTitle(entry.name).setItems(actions) { _, index ->
            when (index) {
                0 -> {
                    val destination = if (left) rightPath else leftPath
                    work({ root.copyPath(entry.path, destination).also { check(it.code == 0) { it.output.ifBlank { "复制失败" } } } }) { refreshBrowsers() }
                }
                1 -> renameBrowserEntry(left, entry)
                2 -> AlertDialog.Builder(this).setMessage(if (entry.type == "d") "删除文件夹“${entry.name}”及其全部内容？" else "删除文件“${entry.name}”？")
                    .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
                        work({ root.deletePath(entry.path, entry.type == "d").also { check(it.code == 0) { it.output.ifBlank { "删除失败" } } } }) { refreshBrowsers() }
                    }.show()
            }
        }.setNegativeButton("取消", null).show()
    }

    private fun renameBrowserEntry(left: Boolean, entry: BrowserEntry) {
        val input = EditText(this).apply { setText(entry.name); setSelection(text.length) }
        AlertDialog.Builder(this).setTitle("重命名").setView(input).setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                work({ root.renamePath(entry.path, input.text.toString().trim()).also { check(it.code == 0) { it.output.ifBlank { "重命名失败" } } } }) {
                    if (left) selectedLeft = null else selectedRight = null
                    refreshBrowsers()
                }
            }.show()
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
        val inputTools = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        inputHistoryButton = button("输入历史") { showInputHistory() }
        val inputPresetButton = button("输入方案") { showInputPresets() }
        inputMemoryButton = button(inputMemoryLabel()) { configureInputMemory() }
        inputTools.addView(inputHistoryButton, LinearLayout.LayoutParams(0, dp(42), 1f))
        inputTools.addView(inputPresetButton, LinearLayout.LayoutParams(0, dp(42), 1f))
        inputTools.addView(inputMemoryButton, LinearLayout.LayoutParams(0, dp(42), 1f))
        area.addView(inputTools)
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
        executionKeyBar?.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(keys) }, FrameLayout.LayoutParams(-1, -1))
        content.addView(area, FrameLayout.LayoutParams(-1, -1))
    }

    private fun syncSources(): MutableList<SyncSource> {
        val saved = prefs.getString("syncSources", null)
        if (saved != null) {
            val sources = try {
                val array = org.json.JSONArray(saved)
                MutableList(array.length()) { i -> array.getJSONObject(i).let { SyncSource(it.getString("label"), it.getString("path")) } }
            } catch (_: Exception) { mutableListOf() }
            if (!prefs.getBoolean("syncSourcesDownloadAdded", false)) {
                val download = "/storage/emulated/0/Download"
                if (sources.none { it.path.trimEnd('/') == download }) sources.add(0, SyncSource("下载", download))
                saveSyncSources(sources)
                prefs.edit().putBoolean("syncSourcesDownloadAdded", true).apply()
            }
            return sources
        }
        return mutableListOf(
            SyncSource("下载", "/storage/emulated/0/Download"),
            SyncSource("QQ", "/storage/emulated/0/Android/data/com.tencent.mobileqq/Tencent/QQfile_recv/"),
            SyncSource("Telegram", "/storage/emulated/0/Android/data/org.telegram.messenger.web/files/Telegram/Telegram Files/")
        ).also {
            saveSyncSources(it)
            prefs.edit().putBoolean("syncSourcesDownloadAdded", true).apply()
        }
    }

    private fun openFavorites() {
        val sources = syncSources()
        val labels = sources.map { "★  ${it.label}  ·  ${it.path}" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("收藏夹")
            .setItems(labels) { _, index -> navigateBrowser(activeBrowserLeft, sources[index].path) }
            .setPositiveButton("管理收藏夹") { _, _ -> manageSyncSources() }
            .setNegativeButton("关闭", null).show()
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
            @JavascriptInterface fun send(value: String) {
                recordTerminalInput(value)
                ExecutionService.active?.send(value)
            }
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

    private fun launch(inputSteps: List<String> = emptyList(), trackInputHistory: Boolean = true) {
        if (SessionState.running) { message("脚本正在运行", "请先结束当前会话。"); return }
        val file = selected ?: run { chooseFile(); return }
        inputHistoryFile = file
        trackInputHistoryForSession = trackInputHistory
        synchronized(inputCaptureLock) {
            inputCaptureBuffer.clear(); skipNextLineFeed = false; skipEscapeSequence = 0; inputCaptureTrackable = true
        }
        SessionState.clear(); terminal?.evaluateJavascript("term.reset()", null)
        val mode = preferredMode()
        SessionState.mode = mode
        terminal?.evaluateJavascript("window.setMode(${JSONObject.quote(mode.key)})", null)
        startForegroundService(Intent(this, ExecutionService::class.java).putExtra("kind", file.kind).putExtra("name", file.name).putExtra("mode", mode.key)
            .putStringArrayListExtra("inputSteps", ArrayList(inputSteps)))
    }

    private fun quickShortcuts(): MutableList<QuickShortcut> = try {
        val array = org.json.JSONArray(prefs.getString("quickShortcuts", "[]"))
        MutableList(array.length()) { i ->
            array.getJSONObject(i).let { item ->
                QuickShortcut(item.getString("name"), item.getString("kind"), item.getString("fileName"), item.optString("presetName").takeIf { it.isNotBlank() })
            }
        }
    } catch (_: Exception) { mutableListOf() }

    private fun saveQuickShortcuts(shortcuts: List<QuickShortcut>) {
        val array = org.json.JSONArray()
        shortcuts.forEach { shortcut ->
            array.put(JSONObject().put("name", shortcut.name).put("kind", shortcut.kind)
                .put("fileName", shortcut.fileName).put("presetName", shortcut.presetName ?: ""))
        }
        prefs.edit().putString("quickShortcuts", array.toString()).apply()
    }

    private fun updateShortcutPresetReferences(file: ManagedFile, oldName: String, newName: String?) {
        val shortcuts = quickShortcuts()
        var changed = false
        val updated = shortcuts.map { shortcut ->
            if (shortcut.kind == file.kind && shortcut.fileName == file.name && shortcut.presetName == oldName) {
                changed = true
                shortcut.copy(presetName = newName)
            } else shortcut
        }
        if (changed) saveQuickShortcuts(updated)
    }

    private fun activeQuickShortcut(): QuickShortcut? {
        val name = prefs.getString("activeQuickShortcut", null) ?: return null
        return quickShortcuts().firstOrNull { it.name == name }
    }

    private fun shortcutButtonLabel() = activeQuickShortcut()?.let { "${it.name} ▾" } ?: "快捷方式 ▾"

    private fun updateShortcutButton() { shortcutButton?.text = shortcutButtonLabel() }

    private fun showQuickShortcuts() {
        val shortcuts = quickShortcuts()
        val rows = shortcuts.map { "${it.name}  ·  ${it.fileName}${it.presetName?.let { name -> "  ·  $name" } ?: ""}" }.toMutableList()
        rows.add("＋ 新建方式")
        AlertDialog.Builder(this).setTitle("选择快捷方式")
            .setItems(rows.toTypedArray()) { _, index ->
                if (index == shortcuts.size) editQuickShortcut(null)
                else {
                    prefs.edit().putString("activeQuickShortcut", shortcuts[index].name).apply()
                    updateShortcutButton()
                    Toast.makeText(this, "已切换到${shortcuts[index].name}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("管理方式") { _, _ -> manageQuickShortcuts() }
            .setNegativeButton("关闭", null).show()
    }

    private fun manageQuickShortcuts() {
        val shortcuts = quickShortcuts()
        val list = column().apply { setPadding(dp(20), dp(8), dp(20), dp(8)) }
        val dialog = AlertDialog.Builder(this).setTitle("管理快捷方式")
            .setView(ScrollView(this).apply { addView(list) })
            .setPositiveButton("新建方式") { _, _ -> editQuickShortcut(null) }
            .setNegativeButton("关闭", null).create()
        if (shortcuts.isEmpty()) list.addView(text("暂无快捷方式，点击“新建方式”添加。", 14f))
        shortcuts.forEach { shortcut ->
            val active = prefs.getString("activeQuickShortcut", null) == shortcut.name
            list.addView(text(shortcut.name + if (active) " · 当前方式" else "", 17f, accent))
            list.addView(text(shortcut.fileName + "\n输入方案：${shortcut.presetName ?: "不使用"}", 13f))
            val actions = LinearLayout(this)
            actions.addView(button("编辑") { dialog.dismiss(); editQuickShortcut(shortcut) }, LinearLayout.LayoutParams(0, dp(46), 1f))
            actions.addView(button("重命名") { dialog.dismiss(); renameQuickShortcut(shortcut) }, LinearLayout.LayoutParams(0, dp(46), 1f))
            actions.addView(button("删除") {
                AlertDialog.Builder(this).setMessage("删除快捷方式“${shortcut.name}”？")
                    .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
                        val current = quickShortcuts()
                        current.removeAll { it.name == shortcut.name }
                        saveQuickShortcuts(current)
                        if (prefs.getString("activeQuickShortcut", null) == shortcut.name)
                            prefs.edit().remove("activeQuickShortcut").apply()
                        updateShortcutButton()
                        dialog.dismiss()
                        manageQuickShortcuts()
                    }.show()
            }, LinearLayout.LayoutParams(0, dp(46), 1f))
            list.addView(actions)
        }
        dialog.show()
    }

    private fun renameQuickShortcut(shortcut: QuickShortcut) {
        val input = EditText(this).apply { setText(shortcut.name); setSelection(text.length); setSingleLine(true) }
        AlertDialog.Builder(this).setTitle("重命名快捷方式").setView(input).setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val name = input.text.toString().trim()
                val current = quickShortcuts()
                val index = current.indexOfFirst { it.name == shortcut.name }
                if (name.isBlank()) { message("名称不能为空", "请填写快捷方式名称。"); return@setPositiveButton }
                if (current.any { it.name.equals(name, true) && it.name != shortcut.name }) { message("名称已存在", "请使用另一个名称。"); return@setPositiveButton }
                if (index >= 0) {
                    current[index] = shortcut.copy(name = name)
                    saveQuickShortcuts(current)
                    if (prefs.getString("activeQuickShortcut", null) == shortcut.name) prefs.edit().putString("activeQuickShortcut", name).apply()
                    updateShortcutButton()
                }
            }.show()
    }

    private fun editQuickShortcut(existing: QuickShortcut?) {
        work({ root.list().let { (result, files) -> check(result.code == 0) { result.output }; files } }) { files ->
            if (files.isEmpty()) { message("暂无脚本", "请先导入 SH 或 ELF 文件，再配置快捷方式。"); return@work }
            var name = existing?.name ?: "方式${quickShortcuts().size + 1}"
            var file = files.firstOrNull { it.kind == existing?.kind && it.name == existing?.fileName } ?: files.first()
            var presetName = existing?.presetName?.takeIf { selected -> readInputPresets(file).any { it.name == selected } }
            val form = column().apply { setPadding(dp(20), dp(8), dp(20), 0) }
            val nameInput = EditText(this).apply { hint = "快捷方式名称"; setSingleLine(true); setText(name) }
            val fileButton = button("脚本：${file.name}") { }
            val presetButton = button("输入方案：${presetName ?: "不使用"}") { }
            fileButton.setOnClickListener {
                AlertDialog.Builder(this).setTitle("选择脚本")
                    .setItems(files.map { "${it.name}  ·  ${if (it.kind == "scripts") "SH" else "ELF"}" }.toTypedArray()) { _, index ->
                        file = files[index]
                        presetName = null
                        fileButton.text = "脚本：${file.name}"
                        presetButton.text = "输入方案：不使用"
                    }.show()
            }
            presetButton.setOnClickListener {
                val presets = readInputPresets(file)
                val options = listOf("不使用输入方案") + presets.map { "${it.name} · ${it.steps.size} 项" }
                AlertDialog.Builder(this).setTitle("选择输入方案").setItems(options.toTypedArray()) { _, index ->
                    presetName = if (index == 0) null else presets[index - 1].name
                    presetButton.text = "输入方案：${presetName ?: "不使用"}"
                }.show()
            }
            form.addView(nameInput)
            form.addView(fileButton)
            form.addView(presetButton)
            form.addView(text("按下 Start 后自动启动脚本并按顺序发送方案输入。", 12f, accent))
            AlertDialog.Builder(this).setTitle(if (existing == null) "新建快捷方式" else "编辑快捷方式")
                .setView(form).setNegativeButton("取消", null).setPositiveButton("保存", null).create().also { dialog ->
                    dialog.setOnShowListener {
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                            name = nameInput.text.toString().trim()
                            val current = quickShortcuts()
                            if (name.isBlank()) { message("名称不能为空", "请填写快捷方式名称。"); return@setOnClickListener }
                            if (current.any { it.name.equals(name, true) && it.name != existing?.name }) { message("名称已存在", "请使用另一个名称。"); return@setOnClickListener }
                            val shortcut = QuickShortcut(name, file.kind, file.name, presetName)
                            val index = current.indexOfFirst { it.name == existing?.name }
                            if (index >= 0) current[index] = shortcut else current.add(shortcut)
                            saveQuickShortcuts(current)
                            if (existing == null || prefs.getString("activeQuickShortcut", null) == existing.name)
                                prefs.edit().putString("activeQuickShortcut", name).apply()
                            updateShortcutButton()
                            dialog.dismiss()
                            manageQuickShortcuts()
                        }
                    }
                    dialog.show()
                }
        }
    }

    private fun startQuickShortcut() {
        if (SessionState.running) { message("脚本正在运行", "当前脚本结束后再启动快捷方式。"); return }
        val shortcuts = quickShortcuts()
        if (shortcuts.isEmpty()) {
            AlertDialog.Builder(this).setTitle("还没有快捷方式")
                .setMessage("请先添加快捷方式，再使用 Start 启动脚本。")
                .setNegativeButton("稍后", null)
                .setPositiveButton("添加快捷方式") { _, _ -> editQuickShortcut(null) }
                .show()
            return
        }
        val shortcut = activeQuickShortcut() ?: run { showQuickShortcuts(); return }
        work({ root.list() }) { (result, files) ->
            if (result.code != 0) { message("读取脚本失败", result.output); return@work }
            val file = files.firstOrNull { it.kind == shortcut.kind && it.name == shortcut.fileName }
            if (file == null) { message("脚本不存在", "快捷方式“${shortcut.name}”对应的文件已删除，请重新编辑快捷方式。"); return@work }
            if (shortcut.presetName != null && readInputPresets(file).none { it.name == shortcut.presetName }) {
                message("输入方案不存在", "快捷方式“${shortcut.name}”的输入方案已删除，请编辑快捷方式。"); return@work
            }
            selected = file
            inputHistoryFile = file
            val preset = shortcut.presetName?.let { name -> readInputPresets(file).firstOrNull { it.name == name } }
            launch(preset?.steps ?: emptyList(), trackInputHistory = false)
            watchQuickExecution(shortcut.name)
        }
    }

    private fun watchQuickExecution(name: String) {
        val started = android.os.SystemClock.elapsedRealtime()
        val check = object : Runnable {
            override fun run() {
                if (isDestroyed) return
                if (SessionState.running) { window.decorView.postDelayed(this, 250); return }
                if (android.os.SystemClock.elapsedRealtime() - started < 500) { window.decorView.postDelayed(this, 250); return }
                val code = SessionState.exitCode
                val succeeded = code == 0
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(if (succeeded) "执行成功" else "执行未成功")
                    .setMessage(if (succeeded) "快捷方式“$name”已完成。" else "快捷方式“$name”已结束。${code?.let { "退出码：$it" } ?: "请查看终端输出。"}")
                    .setPositiveButton("确定", null).show()
            }
        }
        window.decorView.postDelayed(check, 300)
    }

    private fun inputTargetFile(): ManagedFile? = if (SessionState.running) inputHistoryFile else selected

    private fun inputHistoryKey(file: ManagedFile, suffix: String): String {
        val identity = "${file.kind}/${file.name}"
        val encoded = Base64.encodeToString(identity.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        return "scriptInput_${suffix}_$encoded"
    }

    private fun remembersInput(file: ManagedFile): Boolean {
        val key = inputHistoryKey(file, "enabled")
        return if (prefs.contains(key)) prefs.getBoolean(key, false) else prefs.getBoolean("defaultRememberInput", false)
    }

    private fun inputMemoryLabel(): String {
        val file = inputTargetFile()
        return if (file != null && remembersInput(file)) "记忆输入：开" else "记忆输入：关"
    }

    private fun configureInputMemory() {
        val file = inputTargetFile() ?: run { message("先选择脚本", "选择脚本后，可以为它单独开启输入记忆。"); return }
        val enabled = remembersInput(file)
        AlertDialog.Builder(this).setTitle(if (enabled) "关闭输入记忆" else "开启输入记忆")
            .setMessage(if (enabled) "关闭后将停止记录“${file.name}”的新输入，已有历史会保留。" else "只在本机保存“${file.name}”最近提交的输入。若输入包含密码或密钥，请不要开启记忆。")
            .setNegativeButton("取消", null)
            .setPositiveButton(if (enabled) "关闭" else "开启") { _, _ ->
                prefs.edit().putBoolean(inputHistoryKey(file, "enabled"), !enabled).apply()
                if (enabled) synchronized(inputCaptureLock) { inputCaptureBuffer.clear(); inputCaptureTrackable = true }
                inputMemoryButton?.text = inputMemoryLabel()
            }.show()
    }

    private fun readInputHistory(file: ManagedFile): List<String> = try {
        val array = org.json.JSONArray(prefs.getString(inputHistoryKey(file, "history"), "[]"))
        List(array.length()) { array.getString(it) }
    } catch (_: Exception) { emptyList() }

    private fun saveInputHistoryLine(file: ManagedFile, line: String) {
        if (line.isBlank() || line.length > 4096 || !remembersInput(file)) return
        val history = readInputHistory(file).toMutableList().apply { add(line) }.takeLast(20)
        val array = org.json.JSONArray().apply { history.forEach { put(it) } }
        prefs.edit().putString(inputHistoryKey(file, "history"), array.toString()).apply()
    }

    private fun showInputHistory() {
        val file = inputTargetFile() ?: run { message("先选择脚本", "选择并运行脚本后，可以查看它的输入历史。"); return }
        if (!remembersInput(file)) { message("输入记忆已关闭", "先开启“记忆输入”，之后提交的文本输入会保存在“${file.name}”的历史中。"); return }
        if (!SessionState.running || ExecutionService.active == null) { message("脚本未运行", "启动“${file.name}”后，可以从历史中选择并发送输入。"); return }
        val history = readInputHistory(file).asReversed()
        if (history.isEmpty()) { message("暂无输入历史", "提交文本输入后会显示在这里。"); return }
        AlertDialog.Builder(this).setTitle("${file.name} · 输入历史")
            .setItems(history.toTypedArray()) { _, index -> confirmHistoricalInput(history[index]) }
            .setPositiveButton("清空历史") { _, _ ->
                AlertDialog.Builder(this).setMessage("清空“${file.name}”保存的输入历史？")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("清空") { _, _ ->
                        prefs.edit().remove(inputHistoryKey(file, "history")).apply()
                        message("已清空", "“${file.name}”的输入历史已删除。")
                    }.show()
            }
            .setNegativeButton("关闭", null).show()
    }

    private fun confirmHistoricalInput(value: String) {
        val editor = EditText(this).apply { setText(value); setSelection(text.length); setSingleLine(false) }
        AlertDialog.Builder(this).setTitle("确认发送输入").setView(editor)
            .setNegativeButton("取消", null)
            .setPositiveButton("发送") { _, _ ->
                if (SessionState.running && ExecutionService.active != null) {
                    val text = editor.text.toString()
                    recordTerminalInput(text + "\r")
                    ExecutionService.active?.send(text + "\r")
                } else message("脚本已结束", "请重新运行脚本后再发送历史输入。")
            }.show()
    }

    private fun readInputPresets(file: ManagedFile): MutableList<ScriptInputPreset> = try {
        val array = org.json.JSONArray(prefs.getString(inputHistoryKey(file, "presets"), "[]"))
        MutableList(array.length()) { i ->
            array.getJSONObject(i).let { preset ->
                val steps = preset.getJSONArray("steps")
                ScriptInputPreset(preset.getString("name"), List(steps.length()) { steps.getString(it) })
            }
        }
    } catch (_: Exception) { mutableListOf() }

    private fun saveInputPresets(file: ManagedFile, presets: List<ScriptInputPreset>) {
        val array = org.json.JSONArray()
        presets.forEach { preset ->
            val steps = org.json.JSONArray().apply { preset.steps.forEach { put(it) } }
            array.put(JSONObject().put("name", preset.name).put("steps", steps))
        }
        prefs.edit().putString(inputHistoryKey(file, "presets"), array.toString()).apply()
    }

    private fun showInputPresets() {
        val file = inputTargetFile() ?: run { message("先选择脚本", "选择脚本后，可以为它保存输入方案。"); return }
        val presets = readInputPresets(file)
        val builder = AlertDialog.Builder(this).setTitle("${file.name} · 输入方案")
            .setPositiveButton("新建方案") { _, _ -> editInputPreset(file, null) }
            .setNeutralButton("管理方案") { _, _ -> manageInputPresets(file) }
            .setNegativeButton("关闭", null)
        if (presets.isEmpty()) builder.setMessage("还没有输入方案。可以保存一组按顺序逐项发送的文本输入。")
        else builder.setItems(presets.map { "${it.name}  ·  ${it.steps.size} 项" }.toTypedArray()) { _, index ->
            showInputPresetActions(file, index)
        }
        builder.show()
    }

    private fun manageInputPresets(file: ManagedFile) {
        val presets = readInputPresets(file)
        val list = column().apply { setPadding(dp(18), dp(8), dp(18), dp(8)) }
        val dialog = AlertDialog.Builder(this).setTitle("${file.name} · 管理方案")
            .setView(ScrollView(this).apply { addView(list) })
            .setPositiveButton("新建方案") { _, _ -> editInputPreset(file, null) }
            .setNegativeButton("关闭", null).create()
        if (presets.isEmpty()) list.addView(text("暂无输入方案。", 14f))
        presets.forEachIndexed { index, preset ->
            list.addView(text("${preset.name} · ${preset.steps.size} 项", 16f, accent))
            val actions = LinearLayout(this)
            actions.addView(button("编辑") { dialog.dismiss(); editInputPreset(file, index) }, LinearLayout.LayoutParams(0, dp(44), 1f))
            actions.addView(button("重命名") { dialog.dismiss(); renameInputPreset(file, index) }, LinearLayout.LayoutParams(0, dp(44), 1f))
            actions.addView(button("删除") {
                AlertDialog.Builder(this).setMessage("删除输入方案“${preset.name}”？")
                    .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
                        val current = readInputPresets(file)
                        if (index in current.indices) current.removeAt(index)
                        saveInputPresets(file, current)
                        updateShortcutPresetReferences(file, preset.name, null)
                        dialog.dismiss()
                        manageInputPresets(file)
                    }.show()
            }, LinearLayout.LayoutParams(0, dp(44), 1f))
            list.addView(actions)
        }
        dialog.show()
    }

    private fun renameInputPreset(file: ManagedFile, index: Int) {
        val presets = readInputPresets(file)
        val current = presets.getOrNull(index) ?: return
        val input = EditText(this).apply { setText(current.name); setSelection(text.length); setSingleLine(true) }
        val dialog = AlertDialog.Builder(this).setTitle("重命名输入方案").setView(input)
            .setNegativeButton("取消", null).setPositiveButton("保存", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isBlank()) { message("名称不能为空", "请填写方案名称。"); return@setOnClickListener }
                val updated = readInputPresets(file)
                if (updated.any { it.name.equals(name, ignoreCase = true) && it.name != current.name }) {
                    message("名称已存在", "请使用另一个方案名称。"); return@setOnClickListener
                }
                val target = updated.indexOfFirst { it.name == current.name }
                if (target >= 0) updated[target] = current.copy(name = name)
                saveInputPresets(file, updated)
                updateShortcutPresetReferences(file, current.name, name)
                dialog.dismiss()
                manageInputPresets(file)
            }
        }
        dialog.show()
    }

    private fun showInputPresetActions(file: ManagedFile, index: Int) {
        val presets = readInputPresets(file)
        val preset = presets.getOrNull(index) ?: return
        val actions = if (SessionState.running && ExecutionService.active != null)
            arrayOf("逐项发送", "编辑方案", "删除方案")
        else arrayOf("编辑方案", "删除方案")
        AlertDialog.Builder(this).setTitle("${preset.name} · ${preset.steps.size} 项")
            .setItems(actions) { _, action ->
                when (actions[action]) {
                    "逐项发送" -> confirmPresetStep(preset, 0)
                    "编辑方案" -> editInputPreset(file, index)
                    "删除方案" -> AlertDialog.Builder(this).setMessage("删除输入方案“${preset.name}”？")
                        .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
                            val current = readInputPresets(file)
                            if (index in current.indices) current.removeAt(index)
                            saveInputPresets(file, current)
                            showInputPresets()
                        }.show()
                }
            }.setNegativeButton("取消", null).show()
    }

    private fun editInputPreset(file: ManagedFile, index: Int?) {
        val presets = readInputPresets(file)
        val current = index?.let { presets.getOrNull(it) }
        val form = column().apply { setPadding(dp(20), dp(8), dp(20), 0) }
        val nameInput = EditText(this).apply {
            hint = "方案名称，例如：默认配置"
            setSingleLine(true)
            setText(current?.name ?: "")
        }
        val stepsInput = EditText(this).apply {
            hint = "每行一项输入，运行时逐项确认发送"
            minLines = 4
            gravity = Gravity.TOP
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(current?.steps?.joinToString("\n") ?: "")
        }
        form.addView(nameInput)
        form.addView(stepsInput, LinearLayout.LayoutParams(-1, dp(150)))
        form.addView(text("方案保存在本机，请勿保存密码或密钥。", 12f, accent))
        val dialog = AlertDialog.Builder(this).setTitle(if (current == null) "新建输入方案" else "编辑输入方案")
            .setView(form).setNegativeButton("取消", null).setPositiveButton("保存", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text.toString().trim()
                val rawSteps = stepsInput.text.toString()
                if (name.isBlank() || rawSteps.isBlank()) {
                    message("内容不完整", "请填写方案名称和至少一项输入。")
                    return@setOnClickListener
                }
                val steps = rawSteps.split('\n').map { it.removeSuffix("\r") }
                if (steps.size > 50 || steps.any { it.length > 4096 }) {
                    message("方案太长", "每个方案最多 50 项，每项最多 4096 个字符。")
                    return@setOnClickListener
                }
                val updated = readInputPresets(file)
                val duplicate = updated.indexOfFirst { it.name.equals(name, ignoreCase = true) }
                val preset = ScriptInputPreset(name, steps)
                when {
                    index != null && index in updated.indices -> updated[index] = preset
                    duplicate >= 0 -> updated[duplicate] = preset
                    else -> updated.add(preset)
                }
                saveInputPresets(file, updated)
                if (current != null && current.name != name) updateShortcutPresetReferences(file, current.name, name)
                dialog.dismiss()
                showInputPresets()
            }
        }
        dialog.show()
    }

    private fun confirmPresetStep(preset: ScriptInputPreset, index: Int) {
        if (!SessionState.running || ExecutionService.active == null) {
            message("脚本已结束", "方案已停止，没有继续发送后续输入。")
            return
        }
        if (index !in preset.steps.indices) {
            message("输入方案完成", "已完成“${preset.name}”的逐项发送。")
            return
        }
        val editor = EditText(this).apply {
            setText(preset.steps[index])
            setSelection(text.length)
            setSingleLine(true)
        }
        AlertDialog.Builder(this).setTitle("${preset.name} · 第 ${index + 1}/${preset.steps.size} 项")
            .setMessage("确认后发送当前输入。")
            .setView(editor).setNegativeButton("停止方案", null)
            .setPositiveButton(if (index + 1 == preset.steps.size) "发送" else "发送并继续") { _, _ ->
                if (!SessionState.running || ExecutionService.active == null) {
                    message("脚本已结束", "方案已停止，没有继续发送后续输入。")
                    return@setPositiveButton
                }
                val text = editor.text.toString()
                recordTerminalInput(text + "\r")
                ExecutionService.active?.send(text + "\r")
                if (index + 1 < preset.steps.size) window.decorView.postDelayed({ confirmPresetStep(preset, index + 1) }, 180)
                else message("输入方案完成", "已完成“${preset.name}”的逐项发送。")
            }.show()
    }

    private fun recordTerminalInput(value: String) {
        val file = inputHistoryFile ?: return
        if (!trackInputHistoryForSession || !SessionState.running || !remembersInput(file)) return
        synchronized(inputCaptureLock) {
            value.forEach { ch ->
                if (skipNextLineFeed) {
                    skipNextLineFeed = false
                    if (ch == '\n') return@forEach
                }
                if (skipEscapeSequence != 0) {
                    if (skipEscapeSequence == 1) skipEscapeSequence = if (ch == '[' || ch == 'O') 2 else 0
                    else if (ch in '@'..'~') skipEscapeSequence = 0
                    return@forEach
                }
                when (ch) {
                    '\r', '\n' -> {
                        if (inputCaptureTrackable) saveInputHistoryLine(file, inputCaptureBuffer.toString())
                        inputCaptureBuffer.clear(); inputCaptureTrackable = true
                        skipNextLineFeed = ch == '\r'
                    }
                    '\u007f', '\b' -> if (inputCaptureBuffer.isNotEmpty()) {
                        val start = Character.offsetByCodePoints(inputCaptureBuffer, inputCaptureBuffer.length, -1)
                        inputCaptureBuffer.delete(start, inputCaptureBuffer.length)
                    }
                    '\u0015' -> inputCaptureBuffer.clear()
                    '\u0017' -> {
                        while (inputCaptureBuffer.isNotEmpty() && inputCaptureBuffer.last().isWhitespace()) inputCaptureBuffer.deleteCharAt(inputCaptureBuffer.lastIndex)
                        while (inputCaptureBuffer.isNotEmpty() && !inputCaptureBuffer.last().isWhitespace()) inputCaptureBuffer.deleteCharAt(inputCaptureBuffer.lastIndex)
                    }
                    '\u001b' -> { inputCaptureBuffer.clear(); inputCaptureTrackable = false; skipEscapeSequence = 1 }
                    '\u0003', '\u0004' -> { inputCaptureBuffer.clear(); inputCaptureTrackable = true }
                    else -> if (ch < ' ') inputCaptureTrackable = false else inputCaptureBuffer.append(ch)
                }
            }
        }
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
        val quickHome = prefs.getBoolean("homeDefaultQuickInterface", prefs.getBoolean("quickInterface", false))
        area.addView(button("首页默认 · ${if (quickHome) "快捷版" else "完整版"}") {
            AlertDialog.Builder(this).setTitle("首页默认界面")
                .setSingleChoiceItems(arrayOf("完整版", "快捷版"), if (quickHome) 1 else 0) { dialog, index ->
                    val quick = index == 1
                    prefs.edit().putBoolean("homeDefaultQuickInterface", quick).putBoolean("quickInterface", quick).apply()
                    dialog.dismiss()
                    showTab()
                }.setNegativeButton("取消", null).show()
        })
        val rememberByDefault = prefs.getBoolean("defaultRememberInput", false)
        area.addView(button("默认记忆输入 · ${if (rememberByDefault) "开" else "关"}") {
            AlertDialog.Builder(this).setTitle("默认记忆输入")
                .setMessage("此默认值用于没有单独设置过记忆输入的脚本。已有单独设置的脚本保持原样。")
                .setSingleChoiceItems(arrayOf("关闭", "开启"), if (rememberByDefault) 1 else 0) { dialog, index ->
                    prefs.edit().putBoolean("defaultRememberInput", index == 1).apply()
                    dialog.dismiss()
                    showTab()
                }.setNegativeButton("取消", null).show()
        })
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
        area.addView(text("反馈与项目", 13f, accent))
        area.addView(button("反馈频道 · Telegram") { openExternalLink("https://t.me/mbazhuan") })
        area.addView(button("GitHub 仓库") { openExternalLink("https://github.com/yunhe4550-design/Tubing") })
        area.addView(text("tubing ${packageManager.getPackageInfo(packageName, 0).versionName}\nRoot 交互终端", 12f))
        content.addView(ScrollView(this).apply { addView(area) }, FrameLayout.LayoutParams(-1, -1))
    }
    private fun openExternalLink(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            message("无法打开链接", "请确认设备上有可用的浏览器或应用。")
        }
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
    override fun onBackPressed() {
        if (!granted) { super.onBackPressed(); return }
        if (tab == 1 && backBrowserDirectory()) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastBackPressedAt <= 2000L) finishAndRemoveTask()
        else {
            lastBackPressedAt = now
            Toast.makeText(this, "再按一次返回键退出", Toast.LENGTH_SHORT).show()
        }
    }
    override fun onDestroy() { SessionState.unsubscribe(listener); terminalReady = false; terminal?.destroy(); io.shutdown(); super.onDestroy() }
}
