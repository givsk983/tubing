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

private data class ScriptInputPreset(val name: String, val steps: List<String>)
private data class SyncSource(val label: String, val path: String)
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
    private val startButtonHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val startButtonRefresh = object : Runnable {
        override fun run() {
            if (isDestroyed) return
            startButton?.let { renderStartButton(it) }
            if (SessionState.startGate.isPreparing() || SessionState.running ||
                SessionState.startGate.remainingMillis(android.os.SystemClock.elapsedRealtime()) > 0L)
                startButtonHandler.postDelayed(this, 100)
        }
    }
    private var shortcutButton: Button? = null
    private var interfaceModeBar: LinearLayout? = null
    private var interfaceModeButton: Button? = null
    private var managePresetsButton: Button? = null
    private var keyBarToggle: Button? = null
    private var executionKeyBar: FrameLayout? = null
    private var inputHistoryButton: Button? = null
    private var inputMemoryButton: Button? = null
    private var modeSelector: Button? = null
    private var granted = false
    private var checking = false
    private var tab = 0
    private var selected: ManagedFile? = null
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
        backgroundTintList = null
        stateListAnimator = null
        val surface = GradientDrawable().apply {
            setColor(panel); cornerRadius = dp(10).toFloat()
            setStroke(dp(1), if (dark) Color.rgb(61, 83, 105) else Color.rgb(171, 192, 207))
        }
        background = InsetDrawable(android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(Color.argb(40, 40, 185, 207)), surface, null), dp(3))
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
        page.addView(text("Tubing", 40f, accent).apply { typeface = Typeface.MONOSPACE })
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
                } else showGate("未获得 Root 授权，无法进入。\n请在 Root 管理器中允许 Tubing。\n退出码：${result.code}\n${result.output}")
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
        heading.addView(text("Tubing", 27f, accent).apply { typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER_VERTICAL },
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
            managePresetsButton = button("管理方案") { showInputPresets() }
            addView(managePresetsButton, LinearLayout.LayoutParams(-2, dp(42)))
            addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
            keyBarToggle = button("") {
                val collapsed = !prefs.getBoolean("executionKeysCollapsed", false)
                prefs.edit().putBoolean("executionKeysCollapsed", collapsed).apply()
                updateExecutionKeysVisibility()
            }.apply { textSize = 12f }
            addView(keyBarToggle, LinearLayout.LayoutParams(-2, dp(42)))
            addView(interfaceModeButton, LinearLayout.LayoutParams(-2, dp(36)))
        }
        executionKeyBar = FrameLayout(this)
        page.addView(executionKeyBar, LinearLayout.LayoutParams(-1, -2))
        page.addView(interfaceModeBar, LinearLayout.LayoutParams(-1, dp(44)))
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
        managePresetsButton?.visibility = if (quick) View.GONE else View.VISIBLE
        keyBarToggle?.visibility = if (quick) View.GONE else View.VISIBLE
        updateExecutionKeysVisibility()
        interfaceModeBar?.visibility = if (tab == 0) View.VISIBLE else View.GONE
        interfaceModeButton?.text = interfaceModeLabel()
        val heading = page.getChildAt(0) as FrameLayout
        heading.visibility = if (tab == 1 || quick) View.GONE else View.VISIBLE
        startButton = heading.getChildAt(1) as Button
        shortcutButton = heading.getChildAt(2) as Button
        updateShortcutButton()
        startButton?.visibility = View.GONE
        shortcutButton?.visibility = View.GONE
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
        fun surface(color: Int) = GradientDrawable().apply {
            setColor(color); cornerRadius = dp(if (large) 22 else 12).toFloat()
        }
        val states = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), surface(if (dark) Color.rgb(51, 100, 113) else Color.rgb(143, 192, 199)))
            addState(intArrayOf(android.R.attr.state_pressed), surface(Color.rgb(30, 157, 178)))
            addState(intArrayOf(), surface(accent))
        }
        background = InsetDrawable(android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(Color.argb(80, 255, 255, 255)), states, null), dp(3))
        val startView = this
        stateListAnimator = android.animation.StateListAnimator().apply {
            fun scale(value: Float) = android.animation.AnimatorSet().apply {
                playTogether(android.animation.ObjectAnimator.ofFloat(startView, "scaleX", value),
                    android.animation.ObjectAnimator.ofFloat(startView, "scaleY", value))
                duration = 100
            }
            addState(intArrayOf(android.R.attr.state_pressed, android.R.attr.state_enabled), scale(0.96f))
            addState(intArrayOf(), scale(1f))
        }
        renderStartButton(this)
        refreshStartButton()
    }

    private fun renderStartButton(button: Button) {
        val remaining = SessionState.startGate.remainingMillis(android.os.SystemClock.elapsedRealtime())
        val preparing = SessionState.startGate.isPreparing()
        button.isEnabled = !preparing && remaining == 0L && !SessionState.running
        button.text = when {
            preparing -> "准备中…"
            remaining > 0L -> "${(remaining + 999L) / 1_000L} 秒后重试"
            SessionState.running -> "运行中"
            else -> "Start"
        }
    }

    private fun refreshStartButton() {
        startButtonHandler.removeCallbacks(startButtonRefresh)
        startButtonHandler.post(startButtonRefresh)
    }

    private fun quickExecutionPage() {
        val group = column().apply { gravity = Gravity.CENTER_HORIZONTAL }
        group.addView(text("Tubing", 40f, accent).apply {
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
        styledDialog("切换界面模式", "选择适合当前操作的界面", { dialog ->
            column().apply {
                listOf("完整版" to "终端交互、文件管理与完整设置", "快捷版" to "保留常用快捷方式与 Start 启动").forEachIndexed { index, (title, detail) ->
                    addView(optionCard(title, detail, quick == (index == 1)) {
                        prefs.edit().putBoolean("quickInterface", index == 1).apply()
                        dialog.dismiss(); showTab()
                    })
                }
            }
        }, { dialog -> listOf("取消" to { dialog.dismiss() }) })
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
        if (!prefs.getBoolean("fileLibraryHintDismissed", false)) {
            val hint = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(4), dp(4), dp(4))
                background = GradientDrawable().apply {
                    setColor(if (dark) Color.parseColor("#142c38") else Color.parseColor("#e7f7fa"))
                    cornerRadius = dp(10).toFloat()
                }
            }
            hint.addView(text("scripts 用于存放 Shell 脚本，binaries 用于存放 ELF 程序。手动复制的文件需自行放入对应目录。", 12f, mutedColor()),
                LinearLayout.LayoutParams(0, -2, 1f))
            hint.addView(button("知道了") {
                prefs.edit().putBoolean("fileLibraryHintDismissed", true).apply()
                area.removeView(hint)
            }, LinearLayout.LayoutParams(dp(80), dp(48)))
            area.addView(hint, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        val panes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        leftBrowserCaption = null
        rightBrowserCaption = null
        browserTargetLabel = null
        panes.addView(browserPane(true), LinearLayout.LayoutParams(0, -1, 1f))
        val divider = View(this).apply { setBackgroundColor(if (dark) Color.rgb(47, 64, 82) else Color.rgb(204, 216, 225)) }
        panes.addView(divider, LinearLayout.LayoutParams(dp(1), -1))
        panes.addView(browserPane(false), LinearLayout.LayoutParams(0, -1, 1f))
        area.addView(panes, LinearLayout.LayoutParams(-1, 0, 1f))
        browserTargetLabel = text("操作目标：${if (activeBrowserLeft) "左侧 · 执行文件库" else "右侧 · 设备目录"}", 11f, accent).apply {
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
        val caption = text(if (left) "执行文件库" else "设备目录", 13f, if (activeBrowserLeft == left) accent else foreground)
        caption.setOnClickListener { focusBrowser(left) }
        if (left) leftBrowserCaption = caption else rightBrowserCaption = caption
        pane.addView(caption)
        val pathLabel = text(if (left) leftPath else rightPath, 10f, mutedColor()).apply {
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
            val icon = when { entry.type == "d" -> "📁"; entry.type == "l" -> "↪";
                entry.name.endsWith(".zip", true) -> "📦"; entry.type == "f" -> "📄"; else -> "▪" }
            val category = if (entry.type == "d") when (entry.path.trimEnd('/')) {
                "${root.managedRoot}/scripts" -> "Shell 脚本" to "scripts · 存放 .sh 脚本"
                "${root.managedRoot}/binaries" -> "二进制程序" to "binaries · 存放 Android ELF 可执行文件"
                else -> null
            } else null
            row.addView(text("$icon  ${category?.first ?: entry.name}", if (category != null) 13f else 12f, foreground).apply {
                if (category != null) typeface = Typeface.DEFAULT_BOLD
                maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE; setPadding(0, dp(3), 0, dp(1))
            })
            if (category != null) row.addView(text(category.second, 11f, mutedColor()).apply {
                setPadding(0, dp(3), 0, dp(7))
            })
            row.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) focusBrowser(left)
                false
            }
            row.setOnClickListener {
                activeBrowserLeft = left
                if (entry.type == "d") navigateBrowser(left, entry.path, rememberCurrent = true)
                else if (entry.type == "f" && entry.name.endsWith(".zip", true)) openZipBrowser(left, entry)
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
        browserTargetLabel?.text = "操作目标：${if (left) "左侧 · 执行文件库" else "右侧 · 设备目录"}"
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

    private fun showEntryActions(left: Boolean, entry: BrowserEntry) {
        val actions = if (entry.type == "f" && entry.name.endsWith(".zip", true))
            arrayOf("复制到另一侧", "重命名", "删除", "解压 ZIP") else arrayOf("复制到另一侧", "重命名", "删除")
        AlertDialog.Builder(this).setTitle(entry.name).setItems(actions) { _, index ->
            when (index) {
                0 -> {
                    val destination = if (left) rightPath else leftPath
                    work({ root.copyPath(entry.path, destination).also { check(it.code == 0) { it.output.ifBlank { "复制失败" } } } }) { refreshBrowsers() }
                }
                1 -> renameBrowserEntry(left, entry)
                3 -> showExtractZip(left, entry)
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

    private fun showExtractZip(left: Boolean, entry: BrowserEntry) {
        val current = entry.path.substringBeforeLast('/').ifEmpty { "/" }
        val other = if (left) rightPath else leftPath
        var destination = current
        val folder = formInput("新文件夹名称", entry.name.dropLast(4).ifBlank { "解压文件" })
        styledDialog("解压 ZIP", entry.name, {
            column().apply {
                val choices = column()
                fun renderChoices() {
                    choices.removeAllViews()
                    listOf("原目录" to current, "另一侧目录" to other).distinctBy { it.second }.forEach { (label, path) ->
                        choices.addView(optionCard(label, path, destination == path) {
                            destination = path; renderChoices()
                        })
                    }
                }
                renderChoices(); addView(choices)
                addView(text("解压到新文件夹", 13f, accent)); addView(folder)
                addView(text("不会覆盖已有同名文件夹。支持普通 ZIP，暂不支持加密或分卷压缩包。", 12f, mutedColor()))
            }
        }, { dialog -> listOf(
            "取消" to { dialog.dismiss() },
            "解压" to {
                val name = folder.text.toString().trim()
                if (name.isBlank() || name in setOf(".", "..") || name.contains('/') || name.contains('\u0000')) {
                    folder.error = "请输入有效的文件夹名称"
                } else {
                    val target = destination
                    dialog.dismiss()
                    val progress = styledDialog("正在解压", entry.name, { text("请稍候，完成后将自动刷新文件列表。", 14f) }, { emptyList() })
                    progress.setCancelable(false); progress.setCanceledOnTouchOutside(false)
                    io.execute {
                        val result = runCatching { root.extractZip(entry.path, target, name, cacheDir) }
                        runOnUiThread {
                            if (!isDestroyed) {
                                progress.dismiss()
                                if (tab == 1) refreshBrowsers()
                                result.fold({ message("解压完成", it) }, { message("解压失败", it.message ?: "无法读取 ZIP 文件") })
                            }
                        }
                    }
                }
            }
        ) })
    }

    private fun openZipBrowser(left: Boolean, entry: BrowserEntry) {
        val loading = styledDialog("正在打开 ZIP", entry.name, { text("读取文件目录，请稍候…", 14f) }, { emptyList() })
        loading.setCancelable(false); loading.setCanceledOnTouchOutside(false)
        io.execute {
            val result = runCatching {
                val archive = root.prepareZip(entry.path, cacheDir)
                try { archive to ZipExtractor.inspect(archive) }
                catch (error: Exception) { archive.parentFile!!.deleteRecursively(); throw error }
            }
            runOnUiThread {
                if (isDestroyed) {
                    result.getOrNull()?.first?.parentFile?.deleteRecursively()
                } else {
                    loading.dismiss()
                    result.fold({ (archive, items) -> showZipBrowser(left, entry, archive, items) },
                        { message("无法打开 ZIP", it.message ?: "压缩包损坏或格式不受支持") })
                }
            }
        }
    }

    private fun showZipBrowser(left: Boolean, entry: BrowserEntry, archive: File, items: List<ZipItem>) {
        var prefix = ""
        var visibleCount = 200
        val selected = linkedSetOf<String>()
        val listing = column()
        fun render() {
            listing.removeAllViews()
            listing.addView(text("压缩包内：/${prefix}\n已选 ${selected.size} 项 · 长按选择，点击文件预览", 12f, mutedColor()))
            val navigation = LinearLayout(this)
            if (prefix.isNotEmpty()) navigation.addView(button("↑ 上级") {
                prefix = prefix.trimEnd('/').substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
                visibleCount = 200; render()
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
            if (selected.isNotEmpty()) navigation.addView(button("清除选择") { selected.clear(); render() }, LinearLayout.LayoutParams(0, dp(48), 1f))
            listing.addView(navigation)
            val children = items.filter { it.path.startsWith(prefix) && '/' !in it.path.removePrefix(prefix) }
            if (children.isEmpty()) listing.addView(text("目录为空", 14f, mutedColor()))
            children.take(visibleCount).forEach { item ->
                val card = optionCard("${if (item.directory) "📁" else "📄"} ${item.name}",
                    if (item.directory) "文件夹 · 点击进入" else "${item.size} 字节 · 点击预览", item.path in selected) {
                    if (item.directory) { prefix = item.path + "/"; visibleCount = 200; render() }
                    else if (selected.isNotEmpty()) {
                        if (!selected.add(item.path)) selected.remove(item.path)
                        render()
                    } else {
                        work({ ZipExtractor.preview(archive, item) }) { preview ->
                            if (listing.isAttachedToWindow) styledDialog(item.name, "ZIP 内容预览 · ${item.size} 字节", {
                                text(preview.ifEmpty { "空文件" }, 13f).apply {
                                    typeface = Typeface.MONOSPACE; setTextIsSelectable(true)
                                }
                            }, { previewDialog -> listOf(
                                "选择此文件" to { selected.add(item.path); previewDialog.dismiss(); render() },
                                "关闭" to { previewDialog.dismiss() }
                            ) })
                        }
                    }
                }
                card.setOnLongClickListener {
                    if (!selected.add(item.path)) selected.remove(item.path)
                    card.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    render(); true
                }
                listing.addView(card)
            }
            if (children.size > visibleCount) listing.addView(button("显示更多（剩余 ${children.size - visibleCount} 项）") {
                visibleCount += 200; render()
            })
        }
        render()
        val browser = styledDialog("ZIP 文件", entry.name, { listing }, { dialog -> listOf(
            "解压所选" to {
                if (selected.isEmpty()) Toast.makeText(this, "请先长按选择文件或文件夹", Toast.LENGTH_SHORT).show()
                else {
                    val selection = selected.toSet()
                    val original = entry.path.substringBeforeLast('/').ifEmpty { "/" }
                    val opposite = if (left) rightPath else leftPath
                    styledDialog("解压所选 ${selection.size} 项", "直接保存到所选目录，已有同名文件或文件夹时取消操作。", { chooser ->
                        column().apply {
                            listOf("另一侧目录" to opposite, "压缩包所在目录" to original).forEach { (label, path) ->
                                addView(optionCard(label, path) {
                                    chooser.dismiss()
                                    val progress = styledDialog("正在解压", "已选 ${selection.size} 项", { text("请稍候…") }, { emptyList() })
                                    progress.setCancelable(false); progress.setCanceledOnTouchOutside(false)
                                    io.execute {
                                        val result = runCatching { root.extractZipSelection(archive, path, selection) }
                                        runOnUiThread {
                                            if (!isDestroyed) {
                                                progress.dismiss()
                                                result.fold({
                                                    selected.clear(); render()
                                                    if (tab == 1) refreshBrowsers()
                                                    message("解压完成", it)
                                                }, { message("解压失败", it.message ?: "解压失败") })
                                            }
                                        }
                                    }
                                })
                            }
                        }
                    }, { chooser -> listOf("取消" to { chooser.dismiss() }) })
                }
            },
            "关闭" to { dialog.dismiss() }
        ) })
        browser.setOnKeyListener { _, code, event ->
            if (code == KeyEvent.KEYCODE_BACK && (prefix.isNotEmpty() || selected.isNotEmpty())) {
                if (event.action == KeyEvent.ACTION_UP) {
                    if (prefix.isNotEmpty()) {
                        prefix = prefix.trimEnd('/').substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
                        visibleCount = 200
                    } else selected.clear()
                    render()
                }
                true
            } else false
        }
        browser.setOnDismissListener {
            if (!io.isShutdown) io.execute { archive.parentFile!!.deleteRecursively() }
            else archive.parentFile!!.deleteRecursively()
        }
    }

    private fun executionPage() {
        val area = column()
        val actions = LinearLayout(this)
        actions.addView(button("导入文件") { showImportOptions() }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(button("选择文件") { chooseFile() }, LinearLayout.LayoutParams(0, -2, 1f)); area.addView(actions)
        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        controls.addView(text(if (SessionState.running) SessionState.title else selected?.name ?: "选择文件后开始执行", 12f), LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(button("执行") { launch() })
        controls.addView(button("终止") {
            if (SessionState.running) confirmAction("终止执行", "终止当前脚本及其子进程？", "终止") { ExecutionService.active?.terminate() }
        })
        area.addView(controls)
        modeSelector = button(modeText()) { chooseMode() }
        area.addView(modeSelector)
        terminalStatus = text(SessionState.status, 12f, accent); area.addView(terminalStatus)
        val inputTools = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        inputHistoryButton = button("执行历史") { showInputHistory() }
        inputMemoryButton = button(inputMemoryLabel()) { configureInputMemory() }
        inputTools.addView(inputHistoryButton, LinearLayout.LayoutParams(0, dp(42), 1f))
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
        executionKeyBar?.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(keys)
        }, FrameLayout.LayoutParams(-1, dp(46)))
        content.addView(area, FrameLayout.LayoutParams(-1, -1))
    }

    private fun updateExecutionKeysVisibility() {
        val collapsed = prefs.getBoolean("executionKeysCollapsed", false)
        executionKeyBar?.visibility = if (tab == 0 && !prefs.getBoolean("quickInterface", false) && !collapsed) View.VISIBLE else View.GONE
        keyBarToggle?.text = if (collapsed) "展开按键 ▴" else "收起按键 ▾"
        keyBarToggle?.contentDescription = if (collapsed) "向上展开终端快捷键" else "向下收起终端快捷键"
    }

    private fun showImportOptions() {
        styledDialog("导入文件", "选择文件类型或从常用目录导入", { dialog ->
            column().apply {
                addView(optionCard("SH 脚本", "通过系统文件选择器导入 .sh 文件") {
                    dialog.dismiss(); openImportPicker(100)
                })
                addView(optionCard("ELF 二进制", "通过系统文件选择器导入 Android 可执行文件") {
                    dialog.dismiss(); openImportPicker(101)
                })
                addView(optionCard("从同步路径导入", "浏览下载、QQ、Telegram 等已保存路径") {
                    dialog.dismiss(); chooseSyncSource()
                })
            }
        }, { dialog -> listOf("取消" to { dialog.dismiss() }) })
    }

    private fun openImportPicker(requestCode: Int) {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
        }, requestCode)
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
        styledDialog("收藏夹", "点击路径，在当前文件栏打开", { dialog ->
            column().apply {
                if (sources.isEmpty()) addView(text("暂无收藏路径。", 14f, mutedColor()))
                sources.forEach { source ->
                    addView(optionCard(source.label, source.path) { dialog.dismiss(); navigateBrowser(activeBrowserLeft, source.path) })
                }
            }
        }, { dialog -> listOf(
            "管理路径" to { dialog.dismiss(); manageSyncSources() },
            "关闭" to { dialog.dismiss() }
        ) })
    }

    private fun saveSyncSources(sources: List<SyncSource>) {
        val array = org.json.JSONArray()
        sources.forEach { array.put(JSONObject().put("label", it.label).put("path", it.path)) }
        prefs.edit().putString("syncSources", array.toString()).apply()
    }

    private fun manageSyncSources() {
        val sources = syncSources()
        styledDialog("同步路径", "共 ${sources.size} 个路径 · 点击编辑", { dialog ->
            column().apply {
                if (sources.isEmpty()) addView(text("还没有同步路径，点击下方新增。", 14f, mutedColor()))
                sources.forEachIndexed { index, source ->
                    addView(optionCard(source.label, source.path) { dialog.dismiss(); editSyncSource(index) })
                }
            }
        }, { dialog -> listOf(
            "新增路径" to { dialog.dismiss(); editSyncSource(null) },
            "关闭" to { dialog.dismiss() }
        ) })
    }

    private fun chooseSyncSource() {
        val sources = syncSources()
        styledDialog("选择同步路径", "选择常用目录，浏览其中的脚本与文件", { dialog ->
            column().apply {
                if (sources.isEmpty()) addView(text("暂无路径，请先添加同步路径。", 14f, mutedColor()))
                sources.forEach { source ->
                    addView(optionCard(source.label, source.path) { dialog.dismiss(); syncSource(source.path) })
                }
            }
        }, { dialog -> listOf(
            "管理路径" to { dialog.dismiss(); manageSyncSources() },
            "取消" to { dialog.dismiss() }
        ) })
    }

    private fun editSyncSource(index: Int?) {
        val current = index?.let { syncSources().getOrNull(it) }
        val labelInput = formInput("例如：我的下载", current?.label ?: "")
        val pathInput = formInput("/storage/emulated/0/Download", current?.path ?: "", multiline = true).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        styledDialog(if (current == null) "新增同步路径" else "编辑同步路径", "保存常用目录，导入时可快速访问", {
            column().apply {
                addView(text("路径名称", 13f, mutedColor())); addView(labelInput)
                addView(text("绝对路径", 13f, mutedColor())); addView(pathInput)
                addView(text("仅保存目录入口，不会移动或修改目录中的文件。", 12f, mutedColor()))
            }
        }, { dialog ->
            val actions = mutableListOf<Pair<String, () -> Unit>>()
            if (current != null) actions.add("删除" to {
                confirmAction("删除同步路径", "移除“${current.label}”的目录入口？目录中的文件会保留。", "删除") {
                    val updated = syncSources()
                    updated.removeAll { it == current }
                    saveSyncSources(updated); dialog.dismiss(); showTab(); manageSyncSources()
                }
            })
            actions.add("取消" to { dialog.dismiss() })
            actions.add("保存" to save@{
                val label = labelInput.text.toString().trim()
                val path = pathInput.text.toString().trim()
                if (label.isBlank()) {
                    labelInput.error = "请填写路径名称"; labelInput.requestFocus(); return@save
                }
                if (!path.startsWith('/') || path.any { it == '\u0000' || it == '\n' || it == '\r' } ||
                    path.split('/').any { it == ".." }) {
                    pathInput.error = "请输入以 / 开头的有效绝对路径"; pathInput.requestFocus(); return@save
                }
                val updated = syncSources()
                val target = updated.indexOf(current)
                val entry = SyncSource(label, path)
                if (target >= 0) updated[target] = entry else updated.add(entry)
                saveSyncSources(updated); dialog.dismiss(); showTab(); manageSyncSources()
            })
            actions
        })
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
        styledDialog("浏览同步目录", path, { dialog ->
            column().apply {
                entries.forEach { entry ->
                    addView(optionCard(entry.path.substringAfterLast('/'), if (entry.directory) "文件夹 · 点击进入" else "文件 · 点击导入") {
                        dialog.dismiss()
                        if (entry.directory) syncSource(entry.path) else chooseSourceKind(entry.path)
                    })
                }
            }
        }, { dialog -> listOf("关闭" to { dialog.dismiss() }) })
    }

    private fun chooseSourceKind(path: String) {
        val name = path.substringAfterLast('/')
        val options = if (name.endsWith(".sh", true)) arrayOf("导入为 SH 脚本") else arrayOf("导入为 ELF 二进制", "导入为 SH 脚本")
        styledDialog("选择导入类型", name, { dialog ->
            column().apply {
                options.forEachIndexed { index, label ->
                    addView(optionCard(label, if (options.size == 1 || index == 1) "通过 sh 执行" else "校验 ELF 魔数后直接执行") {
                        dialog.dismiss()
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
                    })
                }
            }
        }, { dialog -> listOf("取消" to { dialog.dismiss() }) })
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
    private fun chooseFile(afterSelection: () -> Unit = {}) = work({ root.list().let { (r, files) -> check(r.code == 0) { r.output }; files } }) { files ->
        if (files.isEmpty()) message("暂无文件", "请先导入 SH 或 ELF 文件。")
        else styledDialog("选择执行文件", "共 ${files.size} 个文件 · 点击选择", { dialog ->
            column().apply {
                files.forEach { file ->
                    addView(optionCard(file.name, "${if (file.kind == "scripts") "SH 脚本" else "ELF 二进制"} · 权限 ${file.mode}",
                        selected?.kind == file.kind && selected?.name == file.name) {
                        selected = file; dialog.dismiss(); showTab(); afterSelection()
                    })
                }
            }
        }, { dialog -> listOf("取消" to { dialog.dismiss() }) })
    }

    private fun launch(inputSteps: List<String> = emptyList(), trackInputHistory: Boolean = true): Boolean {
        if (SessionState.running) { message("脚本正在运行", "请先结束当前会话。"); return false }
        val file = selected ?: run { chooseFile(); return false }
        if (!SessionState.startGate.tryStart(android.os.SystemClock.elapsedRealtime())) return false
        synchronized(inputCaptureLock) {
            inputCaptureBuffer.clear(); skipNextLineFeed = false; skipEscapeSequence = 0; inputCaptureTrackable = true
        }
        SessionState.clear(); terminal?.evaluateJavascript("term.reset()", null)
        val mode = preferredMode()
        SessionState.mode = mode
        terminal?.evaluateJavascript("window.setMode(${JSONObject.quote(mode.key)})", null)
        return try {
            startForegroundService(Intent(this, ExecutionService::class.java).putExtra("kind", file.kind).putExtra("name", file.name).putExtra("mode", mode.key)
                .putExtra("rememberInput", remembersInput(file)).putExtra("trackInputHistory", trackInputHistory)
                .putStringArrayListExtra("inputSteps", ArrayList(inputSteps)))
            refreshStartButton()
            true
        } catch (e: Exception) {
            SessionState.startGate.resetAfterFailure()
            refreshStartButton()
            message("启动失败", e.message ?: "无法启动执行服务")
            false
        }
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
        val activeName = activeQuickShortcut()?.name
        val muted = Color.parseColor(if (dark) "#94a9bc" else "#536d82")
        val outline = Color.parseColor(if (dark) "#3d5369" else "#abc0cf")
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val body = column().apply {
            setPadding(dp(20), dp(20), dp(20), dp(16))
            background = GradientDrawable().apply {
                setColor(panel)
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), outline)
            }
        }
        body.addView(text("选择快捷方式", 22f).apply {
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), 0, dp(4), dp(4))
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        })
        body.addView(text(if (shortcuts.isEmpty()) "添加常用脚本，方便下次启动" else "共 ${shortcuts.size} 个方式 · 点击切换后使用 Start 启动", 13f, muted).apply {
            setPadding(dp(4), dp(4), dp(4), dp(16))
        })

        val list = column()
        if (shortcuts.isEmpty()) {
            list.addView(text("暂无快捷方式", 17f).apply {
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(28), dp(12), dp(8))
            })
            list.addView(text("点击下方“新建方式”选择脚本与输入方案。", 13f, muted).apply {
                gravity = Gravity.CENTER
                setPadding(dp(12), 0, dp(12), dp(28))
            })
        }
        shortcuts.forEach { shortcut ->
            val active = shortcut.name == activeName
            val card = column().apply {
                setPadding(dp(14), dp(12), dp(14), dp(12))
                val surface = GradientDrawable().apply {
                    setColor(if (active) Color.parseColor(if (dark) "#142c38" else "#e7f7fa") else this@MainActivity.background)
                    cornerRadius = dp(12).toFloat()
                    setStroke(dp(1), if (active) accent else outline)
                }
                background = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(Color.argb(40, 40, 185, 207)), surface, null)
                isClickable = true
                isFocusable = true
                isSelected = active
                contentDescription = "${shortcut.name}，${if (active) "当前方式，" else ""}文件：${shortcut.fileName}，输入方案：${shortcut.presetName ?: "不使用"}"
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                setOnClickListener {
                    prefs.edit().putString("activeQuickShortcut", shortcut.name).apply()
                    updateShortcutButton()
                    dialog.dismiss()
                    Toast.makeText(this@MainActivity, "已切换到${shortcut.name}", Toast.LENGTH_SHORT).show()
                }
            }
            val heading = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            heading.addView(text(shortcut.name, 17f).apply {
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, 0, dp(8), 0)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            if (active) heading.addView(text("✓ 当前", 12f, accent).apply {
                setPadding(dp(8), dp(4), 0, dp(4))
                typeface = Typeface.DEFAULT_BOLD
            })
            card.addView(heading)
            card.addView(text(shortcut.fileName, 13f, muted).apply {
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                setPadding(0, dp(8), 0, dp(4))
            })
            card.addView(text("输入方案 · ${shortcut.presetName ?: "不使用"}", 12f, muted).apply {
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(2), 0, 0)
            })
            // The whole card is one accessible selection, with a complete untruncated label.
            for (i in 0 until card.childCount)
                card.getChildAt(i).importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
        val scroll = object : ScrollView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val available = (resources.displayMetrics.heightPixels * 0.85f - dp(240)).toInt().coerceAtLeast(dp(48))
                val limit = if (View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.UNSPECIFIED) available
                    else minOf(available, View.MeasureSpec.getSize(heightMeasureSpec))
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.AT_MOST))
            }
        }.apply {
            addView(list)
            clipToPadding = false
        }
        body.addView(scroll, LinearLayout.LayoutParams(-1, -2))
        body.addView(button("＋ 新建方式") { dialog.dismiss(); editQuickShortcut(null) },
            LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(6) })
        val footer = LinearLayout(this)
        footer.addView(button("管理方式") { dialog.dismiss(); manageQuickShortcuts() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        footer.addView(button("关闭") { dialog.dismiss() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        body.addView(footer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        dialog.setContentView(body)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        dialog.window?.setLayout(minOf(resources.displayMetrics.widthPixels - dp(32), dp(480)), -2)
    }

    private fun styledDialog(
        title: String,
        subtitle: String,
        content: (android.app.Dialog) -> View,
        actions: (android.app.Dialog) -> List<Pair<String, () -> Unit>>
    ): android.app.Dialog {
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val body = object : LinearLayout(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val screenLimit = (resources.displayMetrics.heightPixels * 0.88f).toInt()
                val limit = if (View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.UNSPECIFIED) screenLimit
                    else minOf(screenLimit, View.MeasureSpec.getSize(heightMeasureSpec))
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.AT_MOST))
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(16))
            background = GradientDrawable().apply {
                setColor(panel); cornerRadius = dp(20).toFloat()
                setStroke(dp(1), Color.parseColor(if (dark) "#3d5369" else "#abc0cf"))
            }
        }
        body.addView(text(title, 22f).apply {
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), 0, dp(4), dp(4))
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        })
        if (subtitle.isNotBlank()) body.addView(text(subtitle, 13f, mutedColor()).apply {
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setPadding(dp(4), dp(4), dp(4), dp(16))
        })
        val scroll = ScrollView(this).apply { addView(content(dialog)); clipToPadding = false }
        body.addView(scroll, LinearLayout.LayoutParams(-1, -2, 1f).apply {
            if (subtitle.isBlank()) topMargin = dp(12)
        })
        val footer = LinearLayout(this)
        actions(dialog).forEach { (label, action) ->
            footer.addView(button(label, action), LinearLayout.LayoutParams(0, dp(50), 1f))
        }
        body.addView(footer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        dialog.setContentView(body)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialog.window?.setDimAmount(0.6f)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.window?.setLayout(minOf(resources.displayMetrics.widthPixels - dp(32), dp(480)), -2)
        return dialog
    }

    private fun optionCard(title: String, detail: String, active: Boolean = false, onClick: () -> Unit) = dialogCard(active).apply {
        minimumHeight = dp(64)
        background = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(Color.argb(40, 40, 185, 207)), background, null)
        val heading = LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(text(title, 16f).apply {
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setPadding(0, 0, dp(8), 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (active) heading.addView(text("✓ 已选", 12f, accent).apply { setPadding(0, 0, 0, 0) })
        addView(heading)
        if (detail.isNotBlank()) addView(text(detail, 13f, mutedColor()).apply { setPadding(0, dp(7), 0, 0) })
        contentDescription = "$title，$detail${if (active) "，已选" else ""}"
        for (i in 0 until childCount) getChildAt(i).importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        isFocusable = true; isClickable = true; isSelected = active
        setOnClickListener { onClick() }
    }

    private fun formInput(placeholder: String, value: String = "", multiline: Boolean = false) = EditText(this).apply {
        hint = placeholder; setText(value); textSize = 15f
        setTextColor(this@MainActivity.foreground); setHintTextColor(mutedColor())
        setPadding(dp(12), dp(12), dp(12), dp(12))
        minimumHeight = dp(50)
        setSingleLine(!multiline)
        if (multiline) {
            minLines = 2; gravity = Gravity.TOP
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        background = android.graphics.drawable.StateListDrawable().apply {
            fun surface(stroke: Int) = GradientDrawable().apply {
                setColor(this@MainActivity.background); cornerRadius = dp(10).toFloat(); setStroke(dp(1), stroke)
            }
            addState(intArrayOf(android.R.attr.state_focused), surface(accent))
            addState(intArrayOf(), surface(Color.parseColor(if (dark) "#3d5369" else "#abc0cf")))
        }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
    }

    private fun confirmAction(title: String, description: String, actionLabel: String, onConfirm: () -> Unit) {
        styledDialog(title, "", { text(description, 15f) }, { dialog -> listOf(
            "取消" to { dialog.dismiss() },
            actionLabel to { dialog.dismiss(); onConfirm() }
        ) })
    }

    private fun mutedColor() = Color.parseColor(if (dark) "#94a9bc" else "#536d82")

    private fun dialogCard(active: Boolean = false) = column().apply {
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = GradientDrawable().apply {
            setColor(if (active) Color.parseColor(if (dark) "#142c38" else "#e7f7fa") else this@MainActivity.background)
            cornerRadius = dp(12).toFloat()
            setStroke(dp(1), if (active) accent else Color.parseColor(if (dark) "#3d5369" else "#abc0cf"))
        }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
    }

    private fun manageQuickShortcuts() {
        val shortcuts = quickShortcuts()
        styledDialog("管理快捷方式", "共 ${shortcuts.size} 个方式 · 管理脚本与输入方案的组合", { dialog ->
        val list = column()
        if (shortcuts.isEmpty()) list.addView(text("暂无快捷方式，点击下方新建。", 14f, mutedColor()))
        shortcuts.forEach { shortcut ->
            val active = prefs.getString("activeQuickShortcut", null) == shortcut.name
            val card = dialogCard(active)
            val heading = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            heading.addView(text(shortcut.name, 17f).apply {
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, -2, 1f))
            if (active) heading.addView(text("✓ 当前", 12f, accent))
            card.addView(heading)
            card.addView(text(shortcut.fileName, 13f, mutedColor()).apply {
                maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            })
            card.addView(text("输入方案 · ${shortcut.presetName ?: "不使用"}", 12f, mutedColor()).apply {
                maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            })
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
            card.addView(actions)
            list.addView(card)
        }
        list
        }, { dialog -> listOf(
            "＋ 新建方式" to { dialog.dismiss(); editQuickShortcut(null) },
            "关闭" to { dialog.dismiss() }
        ) })
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
        if (!SessionState.startGate.tryPrepare(android.os.SystemClock.elapsedRealtime())) return
        startButton?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        startButton?.let { renderStartButton(it) }
        refreshStartButton()
        io.execute {
            val listing = runCatching { root.list() }
            runOnUiThread {
            SessionState.startGate.finishPreparation()
            if (isDestroyed) return@runOnUiThread
            refreshStartButton()
            val (result, files) = listing.getOrElse {
                message("读取脚本失败", it.message ?: "无法读取脚本列表"); return@runOnUiThread
            }
            if (result.code != 0) { message("读取脚本失败", result.output); return@runOnUiThread }
            val file = files.firstOrNull { it.kind == shortcut.kind && it.name == shortcut.fileName }
            if (file == null) { message("脚本不存在", "快捷方式“${shortcut.name}”对应的文件已删除，请重新编辑快捷方式。"); return@runOnUiThread }
            if (shortcut.presetName != null && readInputPresets(file).none { it.name == shortcut.presetName }) {
                message("输入方案不存在", "快捷方式“${shortcut.name}”的输入方案已删除，请编辑快捷方式。"); return@runOnUiThread
            }
            selected = file
            val preset = shortcut.presetName?.let { name -> readInputPresets(file).firstOrNull { it.name == name } }
            if (launch(preset?.steps ?: emptyList(), trackInputHistory = false)) watchQuickExecution(shortcut.name)
            }
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
        val enabled = selected?.let { remembersInput(it) } ?: prefs.getBoolean("defaultRememberInput", false)
        return if (enabled) "记忆输入：开" else "记忆输入：关"
    }

    private fun configureInputMemory() {
        val file = selected ?: run { chooseFile { configureInputMemory() }; return }
        val enabled = remembersInput(file)
        prefs.edit().putBoolean(inputHistoryKey(file, "enabled"), !enabled).apply()
        inputMemoryButton?.text = inputMemoryLabel()
        Toast.makeText(this, if (enabled) "记忆输入已关闭" else "记忆输入已开启", Toast.LENGTH_SHORT).show()
    }

    private fun readInputHistory(file: ManagedFile): List<String> = try {
        val array = org.json.JSONArray(prefs.getString(inputHistoryKey(file, "history"), "[]"))
        List(array.length()) { array.getString(it) }
    } catch (_: Exception) { emptyList() }

    private fun saveInputHistoryLine(file: ManagedFile, line: String) {
        if (line.isBlank() || line.length > 4096) return
        val history = readInputHistory(file).toMutableList().apply { add(line) }.takeLast(20)
        val array = org.json.JSONArray().apply { history.forEach { put(it) } }
        prefs.edit().putString(inputHistoryKey(file, "history"), array.toString()).apply()
    }

    private fun showInputHistory() {
        val records = ExecutionHistory.read(this).asReversed()
        val dateFormat = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault())
        styledDialog("执行历史", "最近 ${ExecutionHistory.limit(this)} 次 · 包含普通执行和快捷启动", { dialog ->
            column().apply {
                if (records.isEmpty()) addView(text("暂无执行记录。运行脚本后会自动记录，与输入记忆和日志开关无关。", 14f, mutedColor()))
                records.forEach { record ->
                    val card = dialogCard()
                    card.addView(text(record.name, 16f).apply {
                        typeface = Typeface.DEFAULT_BOLD
                        maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                    })
                    val status = if (record.endedAt == null) {
                        if (SessionState.running && SessionState.executionId == record.id) "正在运行" else "未确认结束"
                    } else record.status
                    card.addView(text("${dateFormat.format(java.util.Date(record.startedAt))} · ${ExecutionMode.fromKey(record.mode).label}", 12f, mutedColor()))
                    card.addView(text(status, 13f, accent))
                    card.addView(button("查看记录") { dialog.dismiss(); showExecutionRecord(record) }, LinearLayout.LayoutParams(-1, dp(48)))
                    addView(card)
                }
            }
        }, { dialog -> listOf(
            "清空记录" to {
                styledDialog("清空执行历史", "仅清除执行记录", { text("脚本文件、输入方案与记忆输入均会保留。", 14f) }, { confirm -> listOf(
                    "取消" to { confirm.dismiss() },
                    "清空" to { ExecutionHistory.clear(this); confirm.dismiss(); dialog.dismiss(); showInputHistory() }
                ) })
            },
            "关闭" to { dialog.dismiss() }
        ) })
    }

    private fun showExecutionRecord(record: ExecutionRecord) {
        val file = ManagedFile(record.kind, record.name, "")
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        val status = if (record.endedAt == null) {
            if (SessionState.running && SessionState.executionId == record.id) "正在运行" else "未确认结束（可能被系统中断）"
        } else record.status
        styledDialog("执行记录", record.name, {
            column().apply {
                addView(text("开始：${dateFormat.format(java.util.Date(record.startedAt))}\n" +
                    "结束：${record.endedAt?.let { dateFormat.format(java.util.Date(it)) } ?: "—"}\n" +
                    "方式：${ExecutionMode.fromKey(record.mode).label}\n状态：$status\n退出码：${record.exitCode ?: "—"}", 14f))
                addView(button("查看此脚本的记忆输入") { showRememberedInputs(file) }, LinearLayout.LayoutParams(-1, dp(50)))
            }
        }, { dialog -> listOf(
            "选择此脚本" to {
                work({ root.list() }) { (result, files) ->
                    if (result.code != 0) message("读取脚本失败", result.output)
                    else {
                        val found = files.firstOrNull { it.kind == file.kind && it.name == file.name }
                        if (found == null) message("脚本不存在", "此文件已被删除，执行记录仍会保留。")
                        else { selected = found; dialog.dismiss(); tab = 0; showTab() }
                    }
                }
            },
            "关闭" to { dialog.dismiss() }
        ) })
    }

    private fun showRememberedInputs(file: ManagedFile) {
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
            .setNeutralButton("复制") { _, _ ->
                (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .setPrimaryClip(android.content.ClipData.newPlainText("记忆输入", editor.text.toString()))
                Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
            }
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
        val file = selected ?: run { chooseFile { showInputPresets() }; return }
        val presets = readInputPresets(file)
        styledDialog("输入方案", "为当前脚本保存和管理常用输入", { dialog ->
            column().apply {
                addView(text(file.name, 13f, mutedColor()).apply { setPadding(dp(4), 0, dp(4), dp(14)) })
                addView(button("切换脚本") {
                    dialog.dismiss()
                    chooseFile { showInputPresets() }
                }, LinearLayout.LayoutParams(-1, dp(50)))
                if (presets.isEmpty()) addView(text("还没有输入方案。新建方案后，可在运行时逐项确认发送。", 14f, mutedColor()))
                presets.forEachIndexed { index, preset ->
                    addView(optionCard(preset.name, "${preset.steps.size} 项输入 · 点击查看操作") {
                        dialog.dismiss(); showInputPresetActions(file, index)
                    })
                }
                addView(button("＋ 新建方案") { dialog.dismiss(); editInputPreset(file, null) }, LinearLayout.LayoutParams(-1, dp(50)))
            }
        }, { dialog -> listOf(
            "管理方案" to { dialog.dismiss(); manageInputPresets(file) },
            "关闭" to { dialog.dismiss() }
        ) })
    }

    private fun deleteInputPreset(file: ManagedFile, preset: ScriptInputPreset, onDeleted: () -> Unit) {
        confirmAction("删除输入方案", "删除“${preset.name}”？关联快捷方式将不再使用此方案。", "删除") {
            val current = readInputPresets(file)
            current.removeAll { it.name == preset.name }
            saveInputPresets(file, current)
            updateShortcutPresetReferences(file, preset.name, null)
            onDeleted()
        }
    }

    private fun manageInputPresets(file: ManagedFile) {
        val presets = readInputPresets(file)
        styledDialog("管理输入方案", file.name, { dialog ->
            column().apply {
                if (presets.isEmpty()) addView(text("暂无输入方案，点击下方新建。", 14f, mutedColor()))
                presets.forEachIndexed { index, preset ->
                    val card = dialogCard()
                    card.addView(text(preset.name, 17f).apply { typeface = Typeface.DEFAULT_BOLD })
                    card.addView(text("${preset.steps.size} 项输入", 13f, mutedColor()))
                    val actions = LinearLayout(this@MainActivity)
                    actions.addView(button("编辑") { dialog.dismiss(); editInputPreset(file, index) }, LinearLayout.LayoutParams(0, dp(48), 1f))
                    actions.addView(button("重命名") { dialog.dismiss(); renameInputPreset(file, index) }, LinearLayout.LayoutParams(0, dp(48), 1f))
                    actions.addView(button("删除") {
                        deleteInputPreset(file, preset) { dialog.dismiss(); manageInputPresets(file) }
                    }, LinearLayout.LayoutParams(0, dp(48), 1f))
                    card.addView(actions); addView(card)
                }
            }
        }, { dialog -> listOf(
            "＋ 新建方案" to { dialog.dismiss(); editInputPreset(file, null) },
            "关闭" to { dialog.dismiss() }
        ) })
    }

    private fun renameInputPreset(file: ManagedFile, index: Int) {
        val current = readInputPresets(file).getOrNull(index) ?: return
        val input = formInput("方案名称", current.name)
        styledDialog("重命名输入方案", file.name, { input }, { dialog -> listOf(
            "取消" to { dialog.dismiss() },
            "保存" to save@{
                val name = input.text.toString().trim()
                val updated = readInputPresets(file)
                if (name.isBlank()) { input.error = "请填写方案名称"; input.requestFocus(); return@save }
                if (updated.any { it.name.equals(name, true) && it.name != current.name }) {
                    input.error = "名称已存在，请使用另一个名称"; input.requestFocus(); return@save
                }
                val target = updated.indexOfFirst { it.name == current.name }
                if (target >= 0) updated[target] = current.copy(name = name)
                saveInputPresets(file, updated)
                updateShortcutPresetReferences(file, current.name, name)
                dialog.dismiss(); manageInputPresets(file)
            }
        ) })
    }

    private fun showInputPresetActions(file: ManagedFile, index: Int) {
        val preset = readInputPresets(file).getOrNull(index) ?: return
        val canSend = SessionState.running && ExecutionService.active != null &&
            SessionState.kind == file.kind && SessionState.title == file.name
        styledDialog("方案操作", "${preset.name} · ${preset.steps.size} 项输入", { dialog ->
            column().apply {
                if (canSend) addView(optionCard("逐项发送", "每次确认一项输入后再发送给当前脚本") {
                    dialog.dismiss(); confirmPresetStep(preset, 0)
                })
                addView(optionCard("编辑方案", "修改名称与输入内容") { dialog.dismiss(); editInputPreset(file, index) })
                addView(optionCard("删除方案", "删除本机保存的此输入方案") {
                    deleteInputPreset(file, preset) { dialog.dismiss(); showInputPresets() }
                })
            }
        }, { dialog -> listOf("关闭" to { dialog.dismiss() }) })
    }

    private fun editInputPreset(file: ManagedFile, index: Int?) {
        val current = index?.let { readInputPresets(file).getOrNull(it) }
        val nameInput = formInput("例如：默认配置", current?.name ?: "")
        val stepsInput = formInput("每行一项输入", current?.steps?.joinToString("\n") ?: "", multiline = true).apply { minLines = 4 }
        styledDialog(if (current == null) "新建输入方案" else "编辑输入方案", file.name, {
            column().apply {
                addView(text("方案名称", 13f, mutedColor())); addView(nameInput)
                addView(text("输入内容 · 每行一项", 13f, mutedColor())); addView(stepsInput)
                addView(text("保存在本机，运行时逐项确认发送。请勿保存密码或密钥。", 12f, mutedColor()))
            }
        }, { dialog -> listOf(
            "取消" to { dialog.dismiss() },
            "保存" to save@{
                val name = nameInput.text.toString().trim()
                val rawSteps = stepsInput.text.toString()
                if (name.isBlank()) { nameInput.error = "请填写方案名称"; nameInput.requestFocus(); return@save }
                if (rawSteps.isBlank()) { stepsInput.error = "请填写至少一项输入"; stepsInput.requestFocus(); return@save }
                val steps = rawSteps.split('\n').map { it.removeSuffix("\r") }
                if (steps.size > 50 || steps.any { it.length > 4096 }) {
                    stepsInput.error = "最多 50 项，每项最多 4096 个字符"; stepsInput.requestFocus(); return@save
                }
                val updated = readInputPresets(file)
                if (updated.any { it.name.equals(name, true) && it.name != current?.name }) {
                    nameInput.error = "名称已存在，请使用另一个名称"; nameInput.requestFocus(); return@save
                }
                val target = updated.indexOfFirst { it.name == current?.name }
                val preset = ScriptInputPreset(name, steps)
                if (target >= 0) updated[target] = preset else updated.add(preset)
                saveInputPresets(file, updated)
                if (current != null && current.name != name) updateShortcutPresetReferences(file, current.name, name)
                dialog.dismiss(); showInputPresets()
            }
        ) })
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
        if (!SessionState.trackInputHistory || !SessionState.running || !SessionState.rememberInput) return
        val file = ManagedFile(SessionState.kind, SessionState.title, "")
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
        styledDialog("选择执行方式", "", { dialog ->
            column().apply {
                modes.forEach { mode ->
                    val pipe = mode == ExecutionMode.PIPE
                    addView(optionCard(if (pipe) "管道 · 默认" else "PTY 伪终端",
                        "", mode == preferredMode()) {
                        prefs.edit().putString("executionMode", mode.key).apply()
                        dialog.dismiss(); showTab()
                    })
                }
            }
        }, { dialog -> listOf("取消" to { dialog.dismiss() }) })
    }
    private fun settingsPage() {
        val area = column(); area.addView(text("设置", 25f)); area.addView(text("显示与终端", 13f, accent))
        val quickHome = prefs.getBoolean("homeDefaultQuickInterface", prefs.getBoolean("quickInterface", false))
        area.addView(button("首页默认 · ${if (quickHome) "快捷版" else "完整版"}") {
            styledDialog("首页默认界面", "启动应用时优先打开的界面", { dialog ->
                column().apply {
                    listOf("完整版" to "文件管理、输入方案与完整终端", "快捷版" to "选择快捷方式，快速启动执行").forEachIndexed { index, (label, detail) ->
                        addView(optionCard(label, detail, quickHome == (index == 1)) {
                            val quick = index == 1
                            prefs.edit().putBoolean("homeDefaultQuickInterface", quick).putBoolean("quickInterface", quick).apply()
                            dialog.dismiss()
                            showTab()
                        })
                    }
                }
            }, { dialog -> listOf("取消" to { dialog.dismiss() }) })
        })
        val rememberByDefault = prefs.getBoolean("defaultRememberInput", false)
        area.addView(button("默认记忆输入 · ${if (rememberByDefault) "开" else "关"}") {
            styledDialog("默认记忆输入", "此默认值用于没有单独设置过记忆输入的脚本。已有单独设置的脚本保持原样。", { dialog ->
                column().apply {
                    listOf(false, true).forEach { enabled ->
                        addView(optionCard(if (enabled) "开启" else "关闭",
                            if (enabled) "默认记住脚本的交互输入" else "默认不记住脚本的交互输入", rememberByDefault == enabled) {
                            prefs.edit().putBoolean("defaultRememberInput", enabled).apply()
                            dialog.dismiss()
                            showTab()
                        })
                    }
                }
            }, { dialog -> listOf("取消" to { dialog.dismiss() }) })
        })
        modeSelector = button(modeText()) { chooseMode() }
        area.addView(modeSelector)
        area.addView(button("主题 · " + when (prefs.getString("theme", "system")) { "dark" -> "深色"; "light" -> "浅色"; else -> "跟随系统" }) {
            val themes = listOf(Triple("system", "跟随系统", "自动使用设备的外观设置"),
                Triple("dark", "深色", "深蓝灰背景，适合暗光环境"), Triple("light", "浅色", "明亮背景，适合日间阅读"))
            styledDialog("主题", "选择应用的显示外观", { dialog ->
                column().apply {
                    themes.forEach { (key, label, detail) ->
                        addView(optionCard(label, detail, prefs.getString("theme", "system") == key) {
                            prefs.edit().putString("theme", key).apply(); dialog.dismiss(); recreate()
                        })
                    }
                }
            }, { dialog -> listOf("取消" to { dialog.dismiss() }) })
        })
        area.addView(button("终端字体 · ${prefs.getInt("fontSize", 14)}") {
            val sizes = arrayOf(12, 14, 16, 18, 20)
            styledDialog("终端字体大小", "选择字号 · 下方文字为大小预览", { dialog ->
                column().apply {
                    sizes.forEach { size ->
                        addView(optionCard("$size", "", prefs.getInt("fontSize", 14) == size) {
                            prefs.edit().putInt("fontSize", size).apply()
                            terminal?.evaluateJavascript("window.setSize($size)", null)
                            dialog.dismiss(); showTab()
                        }.apply {
                            addView(text("Tubing 终端 / $ echo hello", size.toFloat(), mutedColor()).apply {
                                typeface = Typeface.MONOSPACE
                                setPadding(0, dp(8), 0, 0)
                                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                            })
                        })
                    }
                }
            }, { dialog -> listOf("取消" to { dialog.dismiss() }) })
        })
        area.addView(Switch(this).apply {
            text = "执行结束后自动保存日志"; setTextColor(this@MainActivity.foreground); isChecked = prefs.getBoolean("saveLogs", true)
            setPadding(dp(10), dp(16), dp(10), dp(16)); setOnCheckedChangeListener { _, enabled -> prefs.edit().putBoolean("saveLogs", enabled).apply() }
        })
        area.addView(text("设备与记录", 13f, accent))
        area.addView(button("执行历史保留 · ${ExecutionHistory.limit(this)} 次") { editExecutionHistoryLimit() })
        area.addView(text("Root 状态 · 已授权"))
        area.addView(button("重新检查 Root") { checkRoot() })
        area.addView(button("查看执行日志") { work({ root.logs() }) { showExecutionLogs(it.output) } })
        area.addView(button("清理临时文件") {
            if (SessionState.running) message("暂时无法清理", "请在执行结束后清理临时文件。")
            else work({ root.cleanTmp() }) { message("清理临时文件", if (it.code == 0) "清理完成" else it.output) }
        })
        area.addView(text("反馈与项目", 13f, accent))
        area.addView(button("反馈频道 · Telegram") { openExternalLink("https://t.me/mbazhuan") })
        area.addView(button("GitHub 仓库") { openExternalLink("https://github.com/yunhe4550-design/Tubing") })
        area.addView(text("Tubing ${packageManager.getPackageInfo(packageName, 0).versionName}\nRoot 交互终端", 12f))
        content.addView(ScrollView(this).apply { addView(area) }, FrameLayout.LayoutParams(-1, -1))
    }
    private fun editExecutionHistoryLimit() {
        val input = formInput("保留次数", ExecutionHistory.limit(this).toString()).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            selectAll()
        }
        styledDialog("执行历史保留次数", "", {
            column().apply {
                addView(text("默认保留 50 次。调小后只保留最近记录，超出部分将删除。", 14f, mutedColor()))
                addView(text("保留次数", 13f, accent))
                addView(input)
                addView(text("请输入大于 0 的整数", 12f, mutedColor()))
            }
        }, { dialog -> listOf(
            "取消" to { dialog.dismiss() },
            "保存" to {
                val limit = input.text.toString().toIntOrNull()
                if (limit == null || limit < 1) {
                    input.error = "请输入大于 0 的整数"
                } else {
                    ExecutionHistory.setLimit(this, limit)
                    dialog.dismiss()
                    showTab()
                }
            }
        ) })
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
    private fun showExecutionLogs(raw: String) {
        if (isDestroyed) return
        val log = LogDisplay.clean(raw).ifBlank { "暂无日志" }
        styledDialog("执行日志", "每个日志文件显示最近 200 行 · 长按可选择文本", {
            text(log, prefs.getInt("fontSize", 14).toFloat()).apply {
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                setLineSpacing(dp(3).toFloat(), 1f)
                background = GradientDrawable().apply {
                    setColor(this@MainActivity.background); cornerRadius = dp(12).toFloat()
                }
            }
        }, { dialog -> listOf(
            "复制日志" to {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("执行日志", log))
                Toast.makeText(this, "日志已复制", Toast.LENGTH_SHORT).show()
            },
            "关闭" to { dialog.dismiss() }
        ) })
    }
    private fun message(title: String, body: String) {
        if (!isDestroyed) styledDialog(title, "", {
            text(body, 15f).apply { setLineSpacing(dp(3).toFloat(), 1f); setTextIsSelectable(true) }
        }, { dialog -> listOf("确定" to { dialog.dismiss() }) })
    }
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
    override fun onDestroy() { startButtonHandler.removeCallbacks(startButtonRefresh); SessionState.unsubscribe(listener); terminalReady = false; terminal?.destroy(); io.shutdown(); super.onDestroy() }
}
