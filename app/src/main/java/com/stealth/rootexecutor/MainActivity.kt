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

private data class ScriptInputPreset(val name: String, val steps: List<String>, val prompts: List<String> = emptyList(), val sensitive: Boolean = false,
    val sendMode: InputSendMode = InputSendMode.DELAY)
private data class SyncSource(val label: String, val path: String)
private data class QuickShortcut(val name: String, val kind: String, val fileName: String, val presetName: String?)
private data class InstalledAutomationApp(val packageName: String, val label: String, val icon: android.graphics.drawable.Drawable)

class MainActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private val browserIo = Executors.newFixedThreadPool(2)
    private val directoryCache = BrowserDirectoryCache()
    private val browserRequests = BrowserRequestTokens()
    private val browserReadTasks = arrayOfNulls<java.util.concurrent.Future<*>>(2)
    private val browserLoading = booleanArrayOf(false, false)
    private val loadedBrowserPaths = arrayOfNulls<String>(2)
    private var browserPanes: LinearLayout? = null
    private val updateIo = Executors.newSingleThreadExecutor()
    private val appPickerIo = Executors.newSingleThreadExecutor()
    private var exportingModule = false
    private var checkingUpdate = false
    private var creatingPackage = false
    private var checkUpdateButton: Button? = null
    private val root = RootExecutor()
    private var browserDirectoryReader: (String) -> Pair<CommandResult, List<BrowserEntry>> = { root.browse(it) }
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
    private var lastRecordedLeftPath: String? = null
    private var lastRecordedRightPath: String? = null
    private var lastBackPressedAt = 0L
    private var leftEntries: List<BrowserEntry> = emptyList()
    private var rightEntries: List<BrowserEntry> = emptyList()
    private var leftBrowserQuery = ""
    private var rightBrowserQuery = ""
    private val leftSelection = BrowserSelection()
    private val rightSelection = BrowserSelection()
    private val leftBrowserRows = mutableMapOf<String, View>()
    private val rightBrowserRows = mutableMapOf<String, View>()
    private val displayedBrowserPaths = mutableMapOf<Boolean, String>()
    private val browserTransitionDirections = mutableMapOf<Boolean, Int>()
    private val browserScrollPositions = object : LinkedHashMap<String, Int>(80, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?) = size > 80
    }
    private var browserSelectionBar: LinearLayout? = null
    private var browserNavigationBar: LinearLayout? = null
    private var browserImportMode = false
    private var browserSelectionCount: TextView? = null
    private var activeBrowserLeft = true
    private var browserTargetLabel: TextView? = null
    private var browserBottomBar: View? = null
    private var favoritesSheet: FavoritesSheet? = null
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
        if (!prefs.getBoolean("privacyDefaultsV1", false)) prefs.edit().putBoolean("saveLogs", false).putBoolean("privacyDefaultsV1", true).apply()
        io.execute { runCatching { PrivateInputStore.migrate(prefs) }.onFailure {
            runOnUiThread { if (!isDestroyed) message("输入数据迁移未完成", "加密存储不可用，原数据已保留。请暂缓自动执行。") }
        } }
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
        val result = FavoritesSwipeLayout(this, { event ->
            val bar = browserBottomBar
            if (!granted || tab != 1 || bar == null || !bar.isShown) false else {
                val barPosition = IntArray(2).also { bar.getLocationOnScreen(it) }
                val pagePosition = IntArray(2).also { page.getLocationOnScreen(it) }
                val y = event.y + pagePosition[1]
                y >= barPosition[1] && y <= pagePosition[1] + page.height - page.paddingBottom
            }
        }, { openFavorites(true) }).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), 0); setBackgroundColor(this@MainActivity.background)
        }
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
        page.addView(button("安装配套模块") { showModuleInstaller() })
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
        if (quick && !browserImportMode) tab = 0
        if (tab != 1 && browserImportMode) {
            browserImportMode = false
            leftSelection.clear(); rightSelection.clear()
        }
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
            1 -> { fileManagerPage(); refreshBrowsers(invalidateCache = false) }
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
        leftBrowserRows.clear()
        rightBrowserRows.clear()
        val area = column()
        val title = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        title.addView(text(if (browserImportMode) "导入文件" else "文件管理", 21f), LinearLayout.LayoutParams(0, dp(48), 1f))
        if (browserImportMode) title.addView(button("取消导入") { closeImportBrowser() })
        title.addView(button("☰") { }.apply {
            textSize = 24f
            contentDescription = "文件管理更多操作"
            minWidth = 0
            setOnClickListener { showBrowserMenu(this) }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        area.addView(title)
        if (!prefs.getBoolean("unifiedFileLibraryHintDismissed", false)) {
            val hint = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(4), dp(4), dp(4))
                background = GradientDrawable().apply {
                    setColor(if (dark) Color.parseColor("#142c38") else Color.parseColor("#e7f7fa"))
                    cornerRadius = dp(10).toFloat()
                }
            }
            hint.addView(text("SH 脚本和 ELF 程序统一存放在 /data/adb/root_executor，可直接导入或复制到此处。", 12f, mutedColor()),
                LinearLayout.LayoutParams(0, -2, 1f))
            hint.addView(button("知道了") {
                prefs.edit().putBoolean("unifiedFileLibraryHintDismissed", true).apply()
                area.removeView(hint)
            }, LinearLayout.LayoutParams(dp(80), dp(48)))
            area.addView(hint, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        val panes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        browserPanes = panes
        leftBrowserCaption = null
        rightBrowserCaption = null
        browserTargetLabel = null
        panes.addView(browserPane(true), LinearLayout.LayoutParams(0, -1, 1f))
        val divider = View(this).apply { setBackgroundColor(if (dark) Color.rgb(47, 64, 82) else Color.rgb(204, 216, 225)) }
        panes.addView(divider, LinearLayout.LayoutParams(dp(1), -1))
        panes.addView(browserPane(false), LinearLayout.LayoutParams(0, -1, 1f))
        area.addView(panes, LinearLayout.LayoutParams(-1, 0, 1f))
        browserTargetLabel = text("操作目标：${if (activeBrowserLeft) "左侧 · 脚本库" else "右侧 · 设备目录"}", 11f, accent).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        browserNavigationBar = column().apply {
            addView(browserTargetLabel, LinearLayout.LayoutParams(-1, dp(34)))
        }
        val navigation = LinearLayout(this).apply { gravity = Gravity.CENTER }
        navigation.addView(button("↑ 上级") { goParent(activeBrowserLeft) }, LinearLayout.LayoutParams(0, dp(46), 1f))
        navigation.addView(button("⌂ 主页") { navigateBrowser(activeBrowserLeft, if (activeBrowserLeft) root.managedRoot else "/storage/emulated/0") }, LinearLayout.LayoutParams(0, dp(46), 1f))
        navigation.addView(button(if (browserImportMode) "更多导入" else "＋ 新建") {
            if (browserImportMode) showSystemImportOptions() else createBrowserDirectory(activeBrowserLeft)
        }, LinearLayout.LayoutParams(0, dp(46), 1f))
        browserNavigationBar?.addView(navigation)
        browserSelectionBar = column().apply {
            val actions = LinearLayout(this@MainActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                browserSelectionCount = text("", 12f, accent)
                addView(browserSelectionCount, LinearLayout.LayoutParams(0, -1, 1f))
                addView(button(if (browserImportMode) "导入所选" else "所选操作") {
                    if (browserImportMode) importBrowserSelection(activeBrowserLeft) else showBrowserSelectionActions(activeBrowserLeft)
                }, LinearLayout.LayoutParams(0, -1, 1f))
                addView(button("取消选择") { clearBrowserSelection(activeBrowserLeft) }, LinearLayout.LayoutParams(0, -1, 1f))
            }
            addView(actions, LinearLayout.LayoutParams(-1, dp(46)))
            addView(text(if (browserImportMode) "方框勾选 · 连续右滑范围选中 · 点 ZIP 名称打开" else "左右滑选中 · 连续右滑范围选中 · 选择时点按追加", 11f, mutedColor()).apply {
                gravity = Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(-1, dp(34)))
        }
        // Both bars participate in measurement; switching modes cannot resize the file panes.
        val bottomBar = FrameLayout(this).apply {
            addView(browserNavigationBar, FrameLayout.LayoutParams(-1, -2))
            addView(browserSelectionBar, FrameLayout.LayoutParams(-1, -2))
        }
        browserBottomBar = bottomBar
        area.addView(bottomBar, LinearLayout.LayoutParams(-1, -2))
        content.addView(area, FrameLayout.LayoutParams(-1, -1))
        updateBrowserSelection()
    }

    private fun browserPane(left: Boolean): LinearLayout {
        val fontSize = browserFontSize().toFloat()
        displayedBrowserPaths[left] = if (left) leftPath else rightPath
        val transition = browserTransitionDirections.remove(left) ?: 0
        val pane = column().apply {
            setPadding(dp(3), 0, dp(3), 0)
            isClickable = true
            setOnClickListener { focusBrowser(left) }
        }
        val caption = text(if (left) "脚本库" else "设备目录", 13f, if (activeBrowserLeft == left) accent else foreground)
        caption.setOnClickListener { focusBrowser(left) }
        if (left) leftBrowserCaption = caption else rightBrowserCaption = caption
        pane.addView(caption)
        val pathLabel = text(if (left) leftPath else rightPath, 10f, mutedColor()).apply {
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setOnClickListener { focusBrowser(left); editBrowserPath(left) }
        }
        pane.addView(pathLabel, LinearLayout.LayoutParams(-1, dp(42)))
        val query = if (left) leftBrowserQuery else rightBrowserQuery
        if (query.isNotEmpty()) pane.addView(button("搜索：$query · 清除") {
            focusBrowser(left)
            if (left) leftBrowserQuery = "" else rightBrowserQuery = ""
            replaceBrowserPane(left)
        }.apply {
            textSize = 11f
            contentDescription = "清除当前栏搜索：$query"
        }, LinearLayout.LayoutParams(-1, -2))
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            setOnClickListener {
                focusBrowser(left)
            }
        }
        val filtered = visibleBrowserEntries(left)
        val selection = browserSelection(left)
        selection.retain(filtered.filter { !browserImportMode || it.type == "f" }.map { it.path })
        val canGoParent = (if (left) leftPath else rightPath).trimEnd('/').isNotEmpty()
        list.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(3), dp(3), dp(5), dp(3))
            background = browserRowBackground()
            addView(text("📁  ..", fontSize, this@MainActivity.foreground).apply {
                maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                setPadding(0, dp(3), 0, dp(1))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            contentDescription = if (canGoParent) "返回上级目录" else "已在根目录"
            setOnClickListener { focusBrowser(left); goParent(left) }
            isEnabled = canGoParent
            alpha = if (canGoParent) 1f else 0.4f
        }, LinearLayout.LayoutParams(-1, -2))
        list.addView(View(this).apply {
            setBackgroundColor(if (dark) Color.rgb(34, 47, 62) else Color.rgb(226, 233, 238))
        }, LinearLayout.LayoutParams(-1, dp(1)))
        if (filtered.isEmpty()) {
            if (browserLoading[if (left) 0 else 1]) {
                list.addView(LinearLayout(this).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(4), dp(12), dp(4), dp(12))
                    addView(ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleSmall).apply {
                        indeterminateTintList = android.content.res.ColorStateList.valueOf(accent)
                    }, LinearLayout.LayoutParams(dp(20), dp(20)))
                    addView(text("正在读取目录…", 12f, mutedColor()))
                })
            } else list.addView(text(if (query.isEmpty()) "目录为空" else "没有匹配的文件或文件夹", fontSize, foreground))
        }
        filtered.forEach { entry ->
            val isSelected = entry.path in selection.selectedPaths
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(3), dp(3), dp(5), dp(3))
                background = browserRowBackground()
                this.isSelected = isSelected
            }
            (if (left) leftBrowserRows else rightBrowserRows)[entry.path] = row
            val icon = when { entry.type == "d" -> "📁"; entry.type == "l" -> "↪";
                entry.name.endsWith(".zip", true) -> "📦"; entry.type == "f" -> "📄"; else -> "▪" }
            if (browserImportMode && entry.type == "f") row.addView(CheckBox(this).apply {
                tag = "importSelectable"
                isChecked = isSelected
                contentDescription = "选择 ${entry.name}"
                buttonTintList = android.content.res.ColorStateList.valueOf(accent)
                setPadding(0, 0, 0, 0)
                setOnClickListener {
                    focusBrowser(left)
                    selection.toggle(entry.path)
                    updateBrowserSelection()
                }
            }, LinearLayout.LayoutParams(dp(40), dp(48)))
            row.addView(text("$icon  ${entry.name}", fontSize, foreground).apply {
                maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE; setPadding(0, dp(3), 0, dp(1))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            installBrowserSwipe(row, left, entry)
            row.setOnClickListener {
                activeBrowserLeft = left
                if (browserImportMode && entry.type == "d") {
                    navigateBrowser(left, entry.path)
                } else if (browserImportMode && entry.type == "l") {
                    Toast.makeText(this, "请选择普通文件，不能导入符号链接", Toast.LENGTH_SHORT).show()
                } else if (browserImportMode && entry.name.endsWith(".zip", true)) {
                    openZipBrowser(left, entry, importMode = true)
                } else if (browserImportMode || selection.inSelectionMode) {
                    selection.add(entry.path)
                    updateBrowserSelection()
                } else if (entry.type == "d") navigateBrowser(left, entry.path)
                else if (entry.type == "f" && entry.name.endsWith(".zip", true)) openZipBrowser(left, entry)
                else {
                    selection.selectOnly(entry.path)
                    updateBrowserSelection()
                }
            }
            row.setOnLongClickListener {
                focusBrowser(left)
                if (browserImportMode) {
                    if (entry.type == "f") {
                        selection.add(entry.path)
                        updateBrowserSelection()
                    } else Toast.makeText(this, "请进入目录选择普通文件", Toast.LENGTH_SHORT).show()
                } else if (selection.inSelectionMode) {
                    selection.add(entry.path)
                    updateBrowserSelection()
                    showBrowserSelectionActions(left)
                } else showEntryActions(left, entry)
                true
            }
            list.addView(row)
            list.addView(View(this).apply { setBackgroundColor(if (dark) Color.rgb(34, 47, 62) else Color.rgb(226, 233, 238)) }, LinearLayout.LayoutParams(-1, dp(1)))
        }
        val scrollKey = "${if (left) "left" else "right"}\u0000${if (left) leftPath else rightPath}\u0000$query\u0000${prefs.getString(if (left) "leftBrowserSort" else "rightBrowserSort", "nameAsc")}"
        val savedOffset = browserScrollPositions[scrollKey] ?: 0
        val scroll = BrowserScrollView(this, accent) { focusBrowser(left) }.apply { addView(list) }
        val motionStyle = browserTransitionStyle()
        val animateDirectory = transition != 0 && android.animation.ValueAnimator.areAnimatorsEnabled()
        if (animateDirectory) {
            list.alpha = motionStyle.startAlpha
            list.translationX = (transition * dp(motionStyle.distanceDp)).toFloat()
        }
        // Restore before the first draw, and only record offsets after the first layout.
        var restored = false
        scroll.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                scroll.viewTreeObserver.removeOnGlobalLayoutListener(this)
                scroll.scrollTo(0, savedOffset)
                restored = true
                browserScrollPositions[scrollKey] = scroll.scrollY
                if (animateDirectory) list.animate()
                    .alpha(1f).translationX(0f).setDuration(motionStyle.durationMs)
                    .setInterpolator(android.view.animation.DecelerateInterpolator(motionStyle.deceleration)).start()
            }
        })
        scroll.setOnScrollChangeListener { _, _, y, _, _ ->
            if (restored) browserScrollPositions[scrollKey] = y
        }
        pane.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return pane
    }

    private fun browserRowBackground(): android.graphics.drawable.Drawable {
        val states = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_selected), android.graphics.drawable.ColorDrawable(
                if (dark) Color.rgb(25, 64, 80) else Color.rgb(210, 239, 243)))
            addState(intArrayOf(), android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        }
        return android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(Color.argb(36, 40, 185, 207)), states,
            android.graphics.drawable.ColorDrawable(Color.WHITE))
    }

    private fun focusBrowser(left: Boolean) {
        lastBackPressedAt = 0L
        if (activeBrowserLeft == left) return
        activeBrowserLeft = left
        browserTargetLabel?.text = "操作目标：${if (left) "左侧 · 脚本库" else "右侧 · 设备目录"}"
        leftBrowserCaption?.setTextColor(if (left) accent else foreground)
        rightBrowserCaption?.setTextColor(if (left) foreground else accent)
        updateBrowserSelection()
    }

    private fun browserSelection(left: Boolean) = if (left) leftSelection else rightSelection

    private fun visibleBrowserEntries(left: Boolean): List<BrowserEntry> {
        val entries = if (left) leftEntries else rightEntries
        return BrowserDisplay.entries(entries, if (left) leftBrowserQuery else rightBrowserQuery,
            BrowserSort.fromKey(prefs.getString(if (left) "leftBrowserSort" else "rightBrowserSort", null)))
    }

    private fun showBrowserMenu(anchor: View) {
        val left = activeBrowserLeft
        val menu = column().apply {
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply {
                setColor(panel); cornerRadius = dp(12).toFloat()
                setStroke(dp(1), if (dark) Color.rgb(61, 83, 105) else Color.rgb(171, 192, 207))
            }
        }
        val popup = PopupWindow(menu, dp(200), -2, true).apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            elevation = dp(12).toFloat()
            isOutsideTouchable = true
        }
        menu.addView(text(if (left) "左侧 · 脚本库" else "右侧 · 设备目录", 12f, mutedColor()))
        listOf<Pair<String, () -> Unit>>(
            "刷新" to { refreshBrowsers() },
            "搜索" to { searchBrowser(left) },
            "排列方式" to { chooseBrowserSort(left) }
        ).forEach { (label, action) ->
            menu.addView(button(label) { popup.dismiss(); action() }, LinearLayout.LayoutParams(-1, dp(48)))
        }
        popup.showAsDropDown(anchor, 0, 0, Gravity.END)
    }

    private fun searchBrowser(left: Boolean) {
        val input = formInput("输入文件或文件夹名称", if (left) leftBrowserQuery else rightBrowserQuery)
        styledDialog("搜索", "仅搜索${if (left) "左" else "右"}栏当前目录，不搜索子目录", { input }, { dialog -> listOf(
            "取消" to { dialog.dismiss() },
            "搜索" to {
                focusBrowser(left)
                clearBrowserSelection(left)
                if (left) leftBrowserQuery = input.text.toString().trim() else rightBrowserQuery = input.text.toString().trim()
                dialog.dismiss()
                replaceBrowserPane(left)
            }
        ) })
    }

    private fun chooseBrowserSort(left: Boolean) {
        val key = if (left) "leftBrowserSort" else "rightBrowserSort"
        val current = BrowserSort.fromKey(prefs.getString(key, null))
        styledDialog("排列方式", "${if (left) "左" else "右"}栏 · 文件夹始终优先，重启后保留", { dialog ->
            column().apply {
                BrowserSort.entries.forEach { sort ->
                    addView(optionCard(sort.label, "", sort == current) {
                        focusBrowser(left)
                        prefs.edit().putString(key, sort.key).apply()
                        dialog.dismiss()
                        replaceBrowserPane(left)
                    })
                }
            }
        }, { dialog -> listOf("取消" to { dialog.dismiss() }) })
    }

    private fun selectableBrowserEntries(left: Boolean) = visibleBrowserEntries(left).filter {
        !browserImportMode || it.type == "f"
    }

    private fun clearBrowserSelection(left: Boolean) {
        browserSelection(left).clear()
        updateBrowserSelection()
    }

    private fun updateBrowserSelection() {
        listOf(true, false).forEach { left ->
            val selection = browserSelection(left)
            val selectedPaths = selection.selectedPaths
            (if (left) leftBrowserRows else rightBrowserRows).forEach { (path, row) ->
                val checked = path in selectedPaths
                if (row.isSelected != checked) row.isSelected = checked
                row.findViewWithTag<CheckBox>("importSelectable")?.let { box ->
                    if (box.isChecked != checked) box.isChecked = checked
                }
            }
        }
        val selection = browserSelection(activeBrowserLeft)
        browserSelectionBar?.visibility = if (selection.inSelectionMode) View.VISIBLE else View.INVISIBLE
        browserNavigationBar?.visibility = if (selection.inSelectionMode) View.INVISIBLE else View.VISIBLE
        browserSelectionCount?.text = "${if (activeBrowserLeft) "左侧" else "右侧"}已选 ${selection.selectedPaths.size} 项"
    }

    private fun installBrowserSwipe(row: View, left: Boolean, entry: BrowserEntry) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        val threshold = maxOf(dp(36), slop * 2)
        var startX = 0f
        var startY = 0f
        var horizontal = false
        var vertical = false
        var cancelled = false
        row.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    focusBrowser(left)
                    startX = event.x; startY = event.y
                    horizontal = false; vertical = false; cancelled = false
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    cancelled = true
                    view.cancelLongPress()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = kotlin.math.abs(event.x - startX)
                    val dy = kotlin.math.abs(event.y - startY)
                    if (!horizontal && !vertical && maxOf(dx, dy) > slop) {
                        horizontal = dx > dy * 1.5f
                        vertical = !horizontal
                        view.cancelLongPress()
                    }
                    if (horizontal) {
                        view.parent.requestDisallowInterceptTouchEvent(true)
                        view.isPressed = false
                        return@setOnTouchListener true
                    }
                }
                MotionEvent.ACTION_UP -> {
                    view.parent.requestDisallowInterceptTouchEvent(false)
                    if (horizontal || vertical || cancelled) {
                        view.isPressed = false
                        view.cancelLongPress()
                        val delta = event.x - startX
                        if (horizontal && !cancelled && kotlin.math.abs(delta) >= threshold) {
                            browserSelection(left).swipe(entry.path, delta > 0, selectableBrowserEntries(left).map { it.path })
                            updateBrowserSelection()
                        }
                        return@setOnTouchListener true
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    view.parent.requestDisallowInterceptTouchEvent(false)
                    horizontal = false; vertical = false; cancelled = true
                }
            }
            false
        }
    }

    private fun replaceBrowserPane(left: Boolean) {
        val panes = browserPanes ?: return
        if (tab != 1 || !panes.isAttachedToWindow) return
        val index = if (left) 0 else 2
        (if (left) leftBrowserRows else rightBrowserRows).clear()
        panes.removeViewAt(index)
        panes.addView(browserPane(left), index, LinearLayout.LayoutParams(0, -1, 1f))
        updateBrowserSelection()
    }

    private fun directoryDirection(from: String?, to: String): Int {
        val old = from?.trimEnd('/')?.ifEmpty { "/" }
        val next = to.trimEnd('/').ifEmpty { "/" }
        return when {
            old == null || old == next -> 0
            next == "/" || old.startsWith("$next/") -> -1
            else -> 1
        }
    }

    private fun refreshBrowsers(invalidateCache: Boolean = true) {
        if (!granted || tab != 1) return
        if (invalidateCache) directoryCache.clear()
        readBrowserDirectory(!activeBrowserLeft)
        readBrowserDirectory(activeBrowserLeft)
    }

    private fun readBrowserDirectory(left: Boolean, from: String? = null) {
        val index = if (left) 0 else 1
        val path = if (left) leftPath else rightPath
        val currentEntries = if (left) leftEntries else rightEntries
        val snapshot = directoryCache[path] ?: currentEntries.takeIf { loadedBrowserPaths[index] == path && !browserLoading[index] }
        val direction = directoryDirection(from, path)
        val token = browserRequests.begin(left)
        browserReadTasks[index]?.cancel(false)
        browserLoading[index] = snapshot == null
        loadedBrowserPaths[index] = if (snapshot != null) path else null
        if (left) leftEntries = snapshot ?: emptyList() else rightEntries = snapshot ?: emptyList()
        browserTransitionDirections[left] = if (snapshot != null) direction else 0
        // Show the new path immediately; cached entries can be used without waiting for su.
        if (from != null || displayedBrowserPaths[left] != path || snapshot == null || snapshot != currentEntries) replaceBrowserPane(left)
        browserReadTasks[index] = browserIo.submit {
            if (!browserRequests.isCurrent(left, token)) return@submit
            val (result, entries) = try { browserDirectoryReader(path) }
                catch (error: Exception) { CommandResult(-1, error.message ?: "读取失败") to emptyList() }
            runOnUiThread {
                if (isDestroyed || !browserRequests.isCurrent(left, token) || path != (if (left) leftPath else rightPath)) return@runOnUiThread
                val wasLoading = browserLoading[index]
                val previous = if (left) leftEntries else rightEntries
                browserLoading[index] = false
                if (result.code == 0) {
                    directoryCache.put(path, entries)
                    loadedBrowserPaths[index] = path
                    if (left) leftEntries = entries else rightEntries = entries
                    val last = if (left) lastRecordedLeftPath else lastRecordedRightPath
                    if (path != last) rememberBrowserDirectory(path)
                    if (left) lastRecordedLeftPath = path else lastRecordedRightPath = path
                } else {
                    directoryCache.remove(path)
                    loadedBrowserPaths[index] = null
                    if (left) leftEntries = emptyList() else rightEntries = emptyList()
                }
                if (tab != 1) return@runOnUiThread
                if (wasLoading || previous != entries || result.code != 0) {
                    browserTransitionDirections[left] = if (wasLoading && result.code == 0) direction else 0
                    replaceBrowserPane(left)
                }
                if (result.code != 0) message("${if (left) "左" else "右"}侧目录无法读取", result.output.ifBlank { "请检查目录路径和访问权限" })
            }
        }
    }

    private fun navigateBrowser(left: Boolean, path: String) {
        lastBackPressedAt = 0L
        val previous = if (left) leftPath else rightPath
        if (previous == path && (if (left) leftBrowserQuery else rightBrowserQuery).isEmpty()) return
        if (left) leftBrowserQuery = "" else rightBrowserQuery = ""
        clearBrowserSelection(left)
        if (left) lastRecordedLeftPath = null else lastRecordedRightPath = null
        if (left) leftPath = path else rightPath = path
        readBrowserDirectory(left, previous)
    }

    private fun goParent(left: Boolean): Boolean {
        val path = if (left) leftPath else rightPath
        if (path.trimEnd('/').isEmpty()) return false
        val parent = path.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" }
        navigateBrowser(left, parent)
        return true
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

    private fun showBrowserSelectionActions(left: Boolean) {
        val selectedPaths = browserSelection(left).selectedPaths
        val entries = visibleBrowserEntries(left).filter { it.path in selectedPaths }
        if (entries.isEmpty()) {
            Toast.makeText(this, "请先滑动选择文件", Toast.LENGTH_SHORT).show()
            return
        }
        if (entries.size == 1) {
            showEntryActions(left, entries.single())
            return
        }
        val destination = if (left) rightPath else leftPath
        AlertDialog.Builder(this).setTitle("已选 ${entries.size} 项")
            .setItems(arrayOf("复制到另一侧", "删除所选")) { _, index ->
                if (index == 0) runBrowserBatch(left, entries, destination)
                else AlertDialog.Builder(this).setTitle("删除所选 ${entries.size} 项？")
                    .setMessage(entries.joinToString("\n") { it.name } + "\n\n所选文件夹及其全部内容也会删除。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("删除") { _, _ -> runBrowserBatch(left, entries, null) }.show()
            }.setNegativeButton("取消", null).show()
    }

    private fun runBrowserBatch(left: Boolean, entries: List<BrowserEntry>, destination: String?) {
        val action = if (destination == null) "删除" else "复制"
        val progress = styledDialog("正在$action", "共 ${entries.size} 项", { text("请稍候…") }, { emptyList() })
        progress.setCancelable(false)
        progress.setCanceledOnTouchOutside(false)
        io.execute {
            val failures = entries.mapNotNull { entry ->
                runCatching {
                    val result = if (destination == null) root.deletePath(entry.path, entry.type == "d")
                        else root.copyPath(entry.path, destination)
                    check(result.code == 0) { result.output.ifBlank { "${action}失败" } }
                }.exceptionOrNull()?.let { entry.path to "${entry.name}：${it.message}" }
            }
            runOnUiThread {
                if (!isDestroyed) {
                    progress.dismiss()
                    if (failures.isEmpty()) clearBrowserSelection(left)
                    else browserSelection(left).retain(failures.map { it.first })
                    refreshBrowsers()
                    message("${action}结果", "成功 ${entries.size - failures.size} 项，失败 ${failures.size} 项" +
                        if (failures.isEmpty()) "" else "\n" + failures.joinToString("\n") { it.second })
                }
            }
        }
    }

    private fun showEntryActions(left: Boolean, entry: BrowserEntry) {
        val actions = if (entry.type == "f" && entry.name.endsWith(".zip", true))
            arrayOf("复制到另一侧", "重命名", "删除", "解压 ZIP")
            else if (entry.type == "f") arrayOf("复制到另一侧", "重命名", "删除", "执行")
            else arrayOf("复制到另一侧", "重命名", "删除")
        AlertDialog.Builder(this).setTitle(entry.name).setItems(actions) { _, index ->
            when (index) {
                0 -> {
                    val destination = if (left) rightPath else leftPath
                    work({ root.copyPath(entry.path, destination).also { check(it.code == 0) { it.output.ifBlank { "复制失败" } } } }) { refreshBrowsers() }
                }
                1 -> renameBrowserEntry(left, entry)
                3 -> if (entry.name.endsWith(".zip", true)) showExtractZip(left, entry) else executeBrowserEntry(entry)
                2 -> AlertDialog.Builder(this).setMessage(if (entry.type == "d") "删除文件夹“${entry.name}”及其全部内容？" else "删除文件“${entry.name}”？")
                    .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
                        work({ root.deletePath(entry.path, entry.type == "d").also { check(it.code == 0) { it.output.ifBlank { "删除失败" } } } }) { refreshBrowsers() }
                    }.show()
            }
        }.setNegativeButton("取消", null).show()
    }

    private fun executeBrowserEntry(entry: BrowserEntry) {
        if (SessionState.running) { message("脚本正在运行", "请先结束当前会话。"); return }
        work({ root.browserProgram(entry.path) }) { file ->
            selected = file
            browserImportMode = false
            leftSelection.clear(); rightSelection.clear()
            tab = 0; showTab()
            launch()
        }
    }

    private fun renameBrowserEntry(left: Boolean, entry: BrowserEntry) {
        val input = EditText(this).apply { setText(entry.name); setSelection(text.length) }
        AlertDialog.Builder(this).setTitle("重命名").setView(input).setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                work({ root.renamePath(entry.path, input.text.toString().trim()).also { check(it.code == 0) { it.output.ifBlank { "重命名失败" } } } }) {
                    clearBrowserSelection(left)
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

    private fun openZipBrowser(left: Boolean, entry: BrowserEntry, importMode: Boolean = false) {
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
                    result.fold({ (archive, items) ->
                        if (importMode) showZipImportOptions(left, entry, archive, items)
                        else showZipBrowser(left, entry, archive, items)
                    },
                        { message("无法打开 ZIP", it.message ?: "压缩包损坏或格式不受支持") })
                }
            }
        }
    }

    private fun showZipBrowser(left: Boolean, entry: BrowserEntry, archive: File, items: List<ZipItem>, importMode: Boolean = false) {
        var prefix = ""
        var visibleCount = 200
        val selected = linkedSetOf<String>()
        val listing = column()
        fun render() {
            listing.removeAllViews()
            listing.addView(text("压缩包内：/${prefix}\n已选 ${selected.size} 项 · 长按选择，点击文件预览" +
                if (importMode) "\n选择 .sh 脚本或 ELF 二进制文件后点击导入所选" else "", 12f, mutedColor()))
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
                    if (importMode && item.directory) {
                        Toast.makeText(this, "请进入目录选择具体文件", Toast.LENGTH_SHORT).show()
                        return@setOnLongClickListener true
                    }
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
        val browser = styledDialog(if (importMode) "从 ZIP 导入" else "ZIP 文件", entry.name, { listing }, { dialog -> if (importMode) listOf(
            "导入所选" to {
                if (selected.isEmpty()) Toast.makeText(this, "请先选择 SH 或 ELF 文件", Toast.LENGTH_SHORT).show()
                else importZipSelection(archive, selected.toSet()) { dialog.dismiss() }
            },
            "关闭" to { dialog.dismiss() }
        ) else listOf(
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
        actions.addView(button("导入文件") { startImport() }, LinearLayout.LayoutParams(0, -2, 1f))
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

    private fun openZipImport(uri: Uri, name: String) {
        val loading = styledDialog("正在打开 ZIP", name, { text("读取文件目录，请稍候…") }, { emptyList() })
        loading.setCancelable(false); loading.setCanceledOnTouchOutside(false)
        io.execute {
            val result = runCatching {
                val staging = File(cacheDir, "zip-import-${java.util.UUID.randomUUID()}")
                check(staging.mkdirs()) { "无法创建导入临时目录" }
                val archive = File(staging, "archive.zip")
                try {
                    (contentResolver.openInputStream(uri) ?: error("无法读取 ZIP")).use { input ->
                        archive.outputStream().use { output ->
                            val buffer = ByteArray(32 * 1024)
                            var total = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                require(total <= ZipExtractor.MAX_ARCHIVE_BYTES) { "ZIP 文件超过 512 MiB" }
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    archive to ZipExtractor.inspect(archive)
                } catch (error: Exception) { staging.deleteRecursively(); throw error }
            }
            runOnUiThread {
                if (isDestroyed) result.getOrNull()?.first?.parentFile?.deleteRecursively()
                else {
                    loading.dismiss()
                    result.fold({ (archive, items) ->
                        showZipImportOptions(true, BrowserEntry("", name, "f", archive.length(), 0), archive, items)
                    }, { message("无法打开 ZIP", it.message ?: "压缩包损坏或格式不受支持") })
                }
            }
        }
    }

    private fun discardZipSnapshot(archive: File) {
        if (!io.isShutdown) io.execute { archive.parentFile?.deleteRecursively() }
        else archive.parentFile?.deleteRecursively()
    }

    private fun showZipImportOptions(left: Boolean, entry: BrowserEntry, archive: File, items: List<ZipItem>) {
        var handedOff = false
        val chooser = styledDialog("ZIP 导入方式", entry.name, { dialog ->
            column().apply {
                addView(optionCard("整个 ZIP 作为项目导入", "保留所有目录、程序和资源；项目名称可修改") {
                    handedOff = true; dialog.dismiss()
                    showProjectImportDialog(archive, entry.name)
                })
                addView(optionCard("浏览并选择包内文件", "预览内容，只导入选中的 SH / ELF 文件") {
                    handedOff = true; dialog.dismiss()
                    showZipBrowser(left, entry, archive, items, importMode = true)
                })
            }
        }, { dialog -> listOf("取消" to { dialog.dismiss() }) })
        chooser.setOnDismissListener { if (!handedOff) discardZipSnapshot(archive) }
    }

    private fun showProjectImportDialog(archive: File, zipName: String) {
        val projectName = formInput("项目名称", ProjectImport.defaultName(zipName))
        var handedOff = false
        val naming = styledDialog("导入 ZIP 项目", zipName, {
            column().apply {
                addView(projectName)
                addView(text("完整解压到 ${root.managedRoot}/项目名称，保留包内结构与资源。不会覆盖已有项目，也不会自动执行。", 13f, mutedColor()))
            }
        }, { dialog -> listOf(
            "取消" to { dialog.dismiss() },
            "导入项目" to {
                val name = projectName.text.toString().trim()
                if (!ProjectImport.validName(name)) projectName.error = "请输入有效项目名称，勿使用路径或系统目录名"
                else {
                    handedOff = true; dialog.dismiss()
                    val progress = styledDialog("正在导入项目", name, { text("解压完整 ZIP 并保存到脚本库，请稍候…") }, { emptyList() })
                    progress.setCancelable(false); progress.setCanceledOnTouchOutside(false)
                    io.execute {
                        val result = runCatching { root.importZipProject(archive, name) }
                        runOnUiThread {
                            if (!isDestroyed) {
                                progress.dismiss()
                                result.fold({ details ->
                                    browserImportMode = false
                                    leftSelection.clear(); rightSelection.clear()
                                    leftPath = "${root.managedRoot}/$name"
                                    lastRecordedLeftPath = null
                                    leftEntries = emptyList()
                                    activeBrowserLeft = true; tab = 1; showTab()
                                    message("项目导入完成", details)
                                    discardZipSnapshot(archive)
                                }, { error ->
                                    // Keep the snapshot so a conflicting name can be corrected without picking again.
                                    Toast.makeText(this, error.message ?: "无法导入 ZIP 项目", Toast.LENGTH_LONG).show()
                                    showProjectImportDialog(archive, "$name.zip")
                                })
                            } else discardZipSnapshot(archive)
                        }
                    }
                }
            }
        ) })
        naming.setOnDismissListener { if (!handedOff) discardZipSnapshot(archive) }
    }

    private fun importZipSelection(archive: File, selection: Set<String>, done: () -> Unit) {
        val progress = styledDialog("正在导入", "已选 ${selection.size} 个文件", { text("校验并导入 SH / ELF 文件，请稍候…") }, { emptyList() })
        progress.setCancelable(false); progress.setCanceledOnTouchOutside(false)
        io.execute {
            val result = runCatching {
                val output = File(archive.parentFile, "selected-${java.util.UUID.randomUUID()}")
                try {
                    require(ZipExtractor.inspect(archive).filter { it.path in selection }.none { it.directory }) { "请选择具体文件，不能导入目录" }
                    ZipExtractor.extract(archive, output, selection = selection)
                    val files = output.listFiles()?.toList().orEmpty()
                    // Validate every selection before publishing any file to the managed directories.
                    val candidates = files.map { it to ZipExtractor.importKind(it) }
                    val imported = mutableListOf<ManagedFile>()
                    val failures = mutableListOf<String>()
                    candidates.forEach { (file, kind) ->
                        runCatching {
                            val response = root.import(file, file.name, kind)
                            check(response.code == 0) { response.output.ifBlank { "导入失败" } }
                            val fields = response.output.lineSequence().firstOrNull { it.startsWith("IMPORTED|") }?.split('|')
                            check(fields?.size == 3) { "未收到导入确认" }
                            ManagedFile(fields!![1], fields[2], "777")
                        }.fold({ imported.add(it) }, { failures.add("${file.name}：${it.message}") })
                    }
                    imported to failures
                } finally { output.deleteRecursively() }
            }
            runOnUiThread {
                if (!isDestroyed) {
                    progress.dismiss()
                    result.fold({ (imported, failures) ->
                        if (imported.isNotEmpty()) {
                            selected = imported.last(); done(); tab = 0; showTab()
                        }
                        message(if (failures.isEmpty()) "导入完成" else "导入结果",
                            "已导入 ${imported.size} 个文件" + if (failures.isEmpty()) "" else "\n失败 ${failures.size} 个：\n${failures.joinToString("\n")}")
                    }, { message("导入失败", it.message ?: "无法导入所选文件") })
                }
            }
        }
    }

    private fun openImportBrowser() {
        leftSelection.clear(); rightSelection.clear()
        browserImportMode = true
        activeBrowserLeft = false
        rightPath = "/storage/emulated/0/Download"
        lastRecordedRightPath = null
        rightEntries = emptyList()
        tab = 1
        showTab()
    }

    private fun closeImportBrowser() {
        browserImportMode = false
        leftSelection.clear(); rightSelection.clear()
        tab = 0
        showTab()
    }

    private fun importBrowserSelection(left: Boolean) {
        val paths = browserSelection(left).selectedPaths
        val entries = selectableBrowserEntries(left).filter { it.path in paths }
        if (entries.isEmpty()) {
            Toast.makeText(this, "请先选择要导入的文件", Toast.LENGTH_SHORT).show()
            return
        }
        val archives = entries.filter { it.name.endsWith(".zip", true) }
        if (archives.isNotEmpty()) {
            if (entries.size == 1) openZipBrowser(left, archives.single(), importMode = true)
            else message("请单独选择 ZIP", "ZIP 可以整包导入为项目，或浏览后选择内部文件。请将不同 ZIP 和普通文件分开导入。")
            return
        }
        val progress = styledDialog("正在导入", "已选 ${entries.size} 个文件", { text("校验并导入 SH / ELF 文件，请稍候…") }, { emptyList() })
        progress.setCancelable(false); progress.setCanceledOnTouchOutside(false)
        io.execute {
            val imported = mutableListOf<ManagedFile>()
            val failures = mutableListOf<Pair<String, String>>()
            entries.forEach { entry ->
                runCatching {
                    val response = root.importBrowserSource(entry.path, entry.name)
                    check(response.code == 0) { response.output.ifBlank { "导入失败" } }
                    val fields = response.output.lineSequence().firstOrNull { it.startsWith("IMPORTED|") }?.split('|')
                    check(fields?.size == 3) { "未收到导入确认" }
                    ManagedFile(fields!![1], fields[2], "777")
                }.fold({ imported.add(it) }, { failures.add(entry.path to "${entry.name}：${it.message}") })
            }
            runOnUiThread {
                if (!isDestroyed) {
                    progress.dismiss()
                    if (imported.isNotEmpty()) selected = imported.last()
                    if (failures.isEmpty()) closeImportBrowser()
                    else {
                        browserSelection(left).retain(failures.map { it.first })
                        updateBrowserSelection()
                    }
                    message(if (failures.isEmpty()) "导入完成" else "导入结果",
                        "已导入 ${imported.size} 个文件" + if (failures.isEmpty()) "" else "\n失败 ${failures.size} 个：\n${failures.joinToString("\n") { it.second }}")
                }
            }
        }
    }

    private fun startImport() = openImportSource(ImportSource.fromKey(prefs.getString("importSource", "ask")))

    private fun openImportSource(source: ImportSource) {
        when (source) {
            ImportSource.ASK -> showSystemImportOptions()
            ImportSource.BUILTIN -> openImportBrowser()
            ImportSource.MT -> {
                val picker = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val targetPackage = listOf("bin.mt.plus", "bin.mt.plus.canary").firstOrNull { name ->
                    picker.setPackage(name).resolveActivity(packageManager) != null
                }
                if (targetPackage == null) {
                    Toast.makeText(this, "未找到支持文件选择的 MT 管理器，请选择其他方式", Toast.LENGTH_LONG).show()
                    showSystemImportOptions()
                } else {
                    picker.setPackage(targetPackage)
                    try { startActivityForResult(picker, 100) }
                    catch (_: android.content.ActivityNotFoundException) { showSystemImportOptions() }
                }
            }
            ImportSource.PHONE -> openImportPicker(100)
        }
    }

    private fun chooseDefaultImportSource() {
        val current = ImportSource.fromKey(prefs.getString("importSource", "ask"))
        styledDialog("默认导入方式", "每次询问，或始终使用指定的文件管理器。导入页面可临时切换来源。", { dialog ->
            column().apply {
                ImportSource.entries.forEach { source ->
                    addView(optionCard(source.label, if (source == ImportSource.ASK) "每次导入时手动选择来源" else "点击导入文件后直接打开", current == source) {
                        prefs.edit().putString("importSource", source.key).apply()
                        dialog.dismiss(); showTab()
                    })
                }
            }
        }, { dialog -> listOf("取消" to { dialog.dismiss() }) })
    }

    private fun showSystemImportOptions() {
        styledDialog("导入文件", "选择导入来源 · 可在设置中指定默认方式", { dialog ->
            column().apply {
                listOf(ImportSource.BUILTIN, ImportSource.MT, ImportSource.PHONE).forEach { source ->
                    addView(optionCard(source.label, when (source) {
                        ImportSource.BUILTIN -> "应用内双栏浏览，勾选、滑动和范围多选"
                        ImportSource.MT -> "调用已安装的 MT 管理器选择程序或 ZIP"
                        else -> "调用手机系统文件选择器选择程序或 ZIP"
                    }) { dialog.dismiss(); openImportSource(source) })
                }
                addView(optionCard("从同步路径导入", "浏览下载、QQ、Telegram 等已保存路径") {
                    dialog.dismiss(); chooseSyncSource()
                })
            }
        }, { dialog -> listOf("取消" to { dialog.dismiss() }) })
    }

    private fun openImportPicker(requestCode: Int) {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, requestCode)
        } catch (_: android.content.ActivityNotFoundException) {
            message("无法打开手机文件管理器", "手机未提供文件选择入口，请使用内置文件管理器或 MT 管理器。")
        }
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

    private fun recentBrowserDirectories(): List<String> = runCatching {
        val array = org.json.JSONArray(prefs.getString("recentBrowserDirectories", "[]"))
        RecentDirectories.clean(List(array.length()) { array.getString(it) })
    }.getOrDefault(emptyList())

    private fun rememberBrowserDirectory(path: String) {
        val array = org.json.JSONArray()
        RecentDirectories.visit(recentBrowserDirectories(), path).forEach { array.put(it) }
        prefs.edit().putString("recentBrowserDirectories", array.toString()).apply()
    }

    private fun openFavorites(): FavoritesSheet = openFavorites(false)

    private fun openFavorites(dragging: Boolean): FavoritesSheet {
        favoritesSheet?.takeIf { it.isShowing }?.let { return it }
        val targetLeft = activeBrowserLeft
        var recent = false
        val listing = column()
        lateinit var dialog: FavoritesSheet
        fun render() {
            listing.removeAllViews()
            val tabs = LinearLayout(this)
            listOf(false to "收藏", true to "最近").forEach { (isRecent, label) ->
                tabs.addView(button(label) { recent = isRecent; render() }.apply {
                    setTextColor(if (recent == isRecent) accent else mutedColor())
                }, LinearLayout.LayoutParams(0, dp(48), 1f))
            }
            listing.addView(tabs)
            if (recent) {
                val paths = recentBrowserDirectories()
                listing.addView(text("最近访问的 ${paths.size} 个目录 · 最多保留 ${RecentDirectories.LIMIT} 个", 12f, mutedColor()))
                if (paths.isEmpty()) listing.addView(text("暂无最近访问目录。", 14f, mutedColor()))
                paths.forEach { path ->
                    val name = when (path) {
                        root.managedRoot -> "脚本库"
                        "/storage/emulated/0" -> "设备存储"
                        "/" -> "/"
                        else -> path.substringAfterLast('/')
                    }
                    listing.addView(optionCard(name, path) {
                        dialog.dismiss(); navigateBrowser(targetLeft, path)
                    })
                }
                if (paths.isNotEmpty()) listing.addView(button("清空最近") {
                    confirmAction("清空最近目录", "只清除最近访问记录，不影响收藏和目录文件。", "清空") {
                        prefs.edit().remove("recentBrowserDirectories").apply()
                        render()
                    }
                })
            } else {
                val sources = syncSources()
                if (sources.isEmpty()) listing.addView(text("暂无收藏路径。", 14f, mutedColor()))
                sources.forEach { source ->
                    listing.addView(optionCard(source.label, source.path) {
                        dialog.dismiss(); navigateBrowser(targetLeft, source.path)
                    })
                }
                listing.addView(button("管理路径") { dialog.dismiss(); manageSyncSources() })
            }
        }
        render()
        val body = dialogPanel(0.60f, 520)
        val heading = text("收藏夹与最近", 22f).apply {
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), 0, dp(4), dp(4))
            contentDescription = "收藏夹与最近，向下拖动关闭"
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        }
        body.addView(heading)
        body.addView(text("点击路径，在${if (targetLeft) "左" else "右"}侧文件栏打开", 13f, mutedColor()).apply {
            setPadding(dp(4), dp(4), dp(4), dp(16))
        })
        body.addView(ScrollView(this).apply { addView(listing) }, LinearLayout.LayoutParams(-1, -2, 1f))
        body.addView(button("关闭") { dialog.dismiss() }, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(10) })
        dialog = FavoritesSheet(findViewById(android.R.id.content), body) {
            if (favoritesSheet === dialog) favoritesSheet = null
        }
        favoritesSheet = dialog
        dialog.attachDragHandle(heading)
        dialog.show(dragging)
        return dialog
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
                    addView(optionCard(source.label, source.path) {
                        dialog.dismiss()
                        if (browserImportMode) navigateBrowser(activeBrowserLeft, source.path) else syncSource(source.path)
                    })
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
        if (name.endsWith(".zip", true)) {
            openZipBrowser(true, BrowserEntry(path, name, "f", 0, 0), importMode = true)
            return
        }
        work({ root.importBrowserSource(path, name).also { check(it.code == 0) { it.output.ifBlank { "导入失败" } } } }) { result ->
            val fields = result.output.lineSequence().firstOrNull { it.startsWith("IMPORTED|") }?.split('|')
            if (fields?.size == 3) selected = ManagedFile(fields[1], fields[2], "777")
            tab = 0; showTab(); message("导入完成", selected?.name ?: "文件已导入")
        }
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
        else showExecutionFilePicker(files, afterSelection)
    }

    private fun showExecutionFilePicker(files: List<ManagedFile>, afterSelection: () -> Unit): android.app.Dialog {
        var prefix = ""
        val listing = column()
        lateinit var dialog: android.app.Dialog
        fun render() {
            listing.removeAllViews()
            listing.addView(text(if (prefix.isEmpty()) "脚本库 · 项目按文件夹显示" else "项目：${prefix.trimEnd('/')}", 13f, accent))
            if (prefix.isNotEmpty()) listing.addView(button("↑ 上级") { prefix = ExecutionLibrary.parent(prefix); render() })
            val folders = ExecutionLibrary.folders(files, prefix)
            val programs = ExecutionLibrary.programs(files, prefix)
            folders.forEach { folder ->
                listing.addView(optionCard("📁 ${folder.name}", "${folder.programCount} 个程序 · 点击进入",
                    selected?.let { it.kind == "project" && it.name.startsWith(folder.prefix) } == true) {
                    prefix = folder.prefix; render()
                })
            }
            programs.forEach { file ->
                listing.addView(optionCard(file.name.substringAfterLast('/'), "${root.filePath(file)} · 权限 ${file.mode}",
                    selected?.kind == file.kind && selected?.name == file.name) {
                    selected = file; dialog.dismiss(); showTab(); afterSelection()
                })
            }
            if (folders.isEmpty() && programs.isEmpty()) listing.addView(text("暂无可执行程序。", 14f, mutedColor()))
        }
        render()
        dialog = styledDialog("选择执行文件", "点击项目进入目录，点击程序完成选择", { listing },
            { chooser -> listOf("取消" to { chooser.dismiss() }) })
        dialog.setOnKeyListener { _, code, event ->
            if (code == KeyEvent.KEYCODE_BACK && prefix.isNotEmpty()) {
                if (event.action == KeyEvent.ACTION_UP) { prefix = ExecutionLibrary.parent(prefix); render() }
                true
            } else false
        }
        return dialog
    }

    private fun launch(inputSteps: List<String> = emptyList(), trackInputHistory: Boolean = true,
        inputPrompts: List<String> = emptyList(), sensitive: Boolean = false, sendMode: InputSendMode = InputSendMode.DELAY,
        quickExecution: Boolean = false): Boolean {
        if (SessionState.running) { message("脚本正在运行", "请先结束当前会话。"); return false }
        val file = selected ?: run { chooseFile(); return false }
        if (!sendMode.valid(inputSteps, inputPrompts)) {
            message("方案配置不完整", "等待提示词模式需要为每项输入填写提示词，也可切换为延时发送。"); return false
        }
        if (!SessionState.startGate.tryStart(android.os.SystemClock.elapsedRealtime())) return false
        QuickExecutionExit.cancel()
        synchronized(inputCaptureLock) {
            inputCaptureBuffer.clear(); skipNextLineFeed = false; skipEscapeSequence = 0; inputCaptureTrackable = true
        }
        SessionState.clear(); terminal?.evaluateJavascript("term.reset()", null)
        val mode = preferredMode()
        SessionState.mode = mode
        terminal?.evaluateJavascript("window.setMode(${JSONObject.quote(mode.key)})", null)
        return try {
            startForegroundService(Intent(this, ExecutionService::class.java).putExtra("kind", file.kind).putExtra("name", file.name).putExtra("mode", mode.key)
                .putExtra(QuickExecutionExit.SHORTCUT, quickExecution)
                .putExtra("rememberInput", !sensitive && remembersInput(file)).putExtra("trackInputHistory", !sensitive && trackInputHistory)
                .putStringArrayListExtra("inputPrompts", ArrayList(inputPrompts))
                .putExtra("inputSendMode", sendMode.key)
                .putStringArrayListExtra("sensitiveValues", ArrayList(if (sensitive) inputSteps else emptyList()))
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

    private fun dialogPanel(heightFraction: Float = 0.88f, maximumHeightDp: Int = Int.MAX_VALUE): LinearLayout = object : LinearLayout(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val screenLimit = minOf((resources.displayMetrics.heightPixels * heightFraction).toInt(),
                    if (maximumHeightDp == Int.MAX_VALUE) Int.MAX_VALUE else dp(maximumHeightDp))
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

    private fun styledDialog(
        title: String,
        subtitle: String,
        content: (android.app.Dialog) -> View,
        actions: (android.app.Dialog) -> List<Pair<String, () -> Unit>>
    ): android.app.Dialog {
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val body = dialogPanel()
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
            val form = column()
            val nameInput = formInput("快捷方式名称", name)
            val fileButton = button("脚本：${file.name}") { }
            val presetButton = button("输入方案：${presetName ?: "不使用"}") { }
            fileButton.setOnClickListener {
                styledDialog("选择脚本", "选择此快捷方式要运行的文件", { dialog ->
                    column().apply {
                        files.forEach { candidate ->
                            addView(optionCard(candidate.name, "", candidate.kind == file.kind && candidate.name == file.name) {
                                file = candidate
                                presetName = null
                                fileButton.text = "脚本：${file.name}"
                                presetButton.text = "输入方案：不使用"
                                dialog.dismiss()
                            })
                        }
                    }
                }, { dialog -> listOf("取消" to { dialog.dismiss() }) })
            }
            presetButton.setOnClickListener {
                val presets = readInputPresets(file)
                styledDialog("选择输入方案", file.name, { dialog ->
                    column().apply {
                        addView(optionCard("不使用输入方案", "", presetName == null) {
                            presetName = null
                            presetButton.text = "输入方案：不使用"
                            dialog.dismiss()
                        })
                        presets.forEach { preset ->
                            addView(optionCard(preset.name, "${preset.steps.size} 项输入", presetName == preset.name) {
                                presetName = preset.name
                                presetButton.text = "输入方案：${preset.name}"
                                dialog.dismiss()
                            })
                        }
                        addView(button("＋ 新建输入方案") {
                            dialog.dismiss()
                            editInputPreset(file, null) { preset ->
                                presetName = preset.name
                                presetButton.text = "输入方案：${preset.name}"
                            }
                        }, LinearLayout.LayoutParams(-1, dp(50)))
                    }
                }, { dialog -> listOf("取消" to { dialog.dismiss() }) })
            }
            form.addView(text("快捷方式名称", 13f, mutedColor()))
            form.addView(nameInput)
            form.addView(fileButton)
            form.addView(presetButton)
            form.addView(text("按下 Start 后自动启动脚本并按顺序发送方案输入。", 12f, accent))
            styledDialog(if (existing == null) "新建快捷方式" else "编辑快捷方式", "", { form }, { dialog -> listOf(
                        "取消" to { dialog.dismiss() },
                        "保存" to save@{
                            name = nameInput.text.toString().trim()
                            val current = quickShortcuts()
                            if (name.isBlank()) { nameInput.error = "请填写快捷方式名称"; nameInput.requestFocus(); return@save }
                            if (current.any { it.name.equals(name, true) && it.name != existing?.name }) { nameInput.error = "名称已存在，请使用另一个名称"; nameInput.requestFocus(); return@save }
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
            ) })
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
            if (launch(preset?.steps ?: emptyList(), trackInputHistory = false,
                    inputPrompts = preset?.prompts ?: emptyList(), sensitive = preset?.sensitive ?: false,
                    sendMode = preset?.sendMode ?: InputSendMode.DELAY, quickExecution = true)) watchQuickExecution(shortcut.name)
            }
        }
    }

    private fun watchQuickExecution(name: String) {
        val started = android.os.SystemClock.elapsedRealtime()
        val check = object : Runnable {
            override fun run() {
                if (isDestroyed) return
                if (SessionState.running) { window.decorView.postDelayed(this, 250); return }
                if (QuickExecutionExit.pending()) return
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
        val array = org.json.JSONArray(PrivateInputStore.read(prefs, inputHistoryKey(file, "history")))
        List(array.length()) { array.getString(it) }
    } catch (_: Exception) { emptyList() }

    private fun saveInputHistoryLine(file: ManagedFile, line: String) {
        if (line.isBlank() || line.length > 4096) return
        val history = readInputHistory(file).toMutableList().apply { add(line) }.takeLast(20)
        val array = org.json.JSONArray().apply { history.forEach { put(it) } }
        PrivateInputStore.write(prefs, inputHistoryKey(file, "history"), array.toString())
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
                work({ if (file.kind == "external") CommandResult(0, "") to listOf(root.browserProgram(file.name)) else root.list() }) { (result, files) ->
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
        val array = org.json.JSONArray(PrivateInputStore.read(prefs, inputHistoryKey(file, "presets")))
        MutableList(array.length()) { i ->
            array.getJSONObject(i).let { preset ->
                val steps = preset.getJSONArray("steps")
                val prompts = preset.optJSONArray("prompts")
                ScriptInputPreset(preset.getString("name"), List(steps.length()) { steps.getString(it) },
                    List(steps.length()) { prompts?.optString(it) ?: "" }, preset.optBoolean("sensitive", false), InputSendMode.fromKey(preset.optString("sendMode")))
            }
        }
    } catch (_: Exception) { mutableListOf() }

    private fun saveInputPresets(file: ManagedFile, presets: List<ScriptInputPreset>) {
        val array = org.json.JSONArray()
        presets.forEach { preset ->
            val steps = org.json.JSONArray().apply { preset.steps.forEach { put(it) } }
            array.put(JSONObject().put("name", preset.name).put("steps", steps)
                .put("prompts", org.json.JSONArray(preset.prompts)).put("sensitive", preset.sensitive).put("sendMode", preset.sendMode.key))
        }
        PrivateInputStore.write(prefs, inputHistoryKey(file, "presets"), array.toString())
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
                    card.addView(text("${preset.steps.size} 项输入 · ${preset.sendMode.label}", 13f, mutedColor()))
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

    private fun editInputPreset(file: ManagedFile, index: Int?, onSaved: ((ScriptInputPreset) -> Unit)? = null) {
        val current = index?.let { readInputPresets(file).getOrNull(it) }
        val nameInput = formInput("例如：默认配置", current?.name ?: "")
        val stepsInput = formInput("每行一项输入", current?.steps?.joinToString("\n") ?: "", multiline = true).apply { minLines = 4 }
        val promptsInput = formInput("每行一项等待提示，例如：请选择操作", current?.prompts?.joinToString("\n") ?: "", multiline = true).apply { minLines = 3 }
        var sendMode = current?.sendMode ?: InputSendMode.DELAY
        val promptFields = column().apply {
            addView(text("等待提示词 · 与输入逐行对应，按原文匹配", 13f, mutedColor())); addView(promptsInput)
            visibility = if (sendMode == InputSendMode.PROMPT) View.VISIBLE else View.GONE
        }
        val modeHint = text("", 12f, mutedColor())
        val modeGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val delayId = View.generateViewId()
        val promptId = View.generateViewId()
        listOf(delayId to InputSendMode.DELAY, promptId to InputSendMode.PROMPT).forEach { (id, mode) ->
            modeGroup.addView(RadioButton(this).apply {
                this.id = id; text = mode.label; textSize = 14f; minHeight = dp(48)
                setTextColor(this@MainActivity.foreground)
                buttonTintList = android.content.res.ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(accent, mutedColor()))
            }, RadioGroup.LayoutParams(0, -2, 1f))
        }
        fun updateSendMode() {
            promptFields.visibility = if (sendMode == InputSendMode.PROMPT) View.VISIBLE else View.GONE
            modeHint.text = if (sendMode == InputSendMode.DELAY) "启动就绪后等待 1.5 秒，之后每项间隔 0.9 秒发送；无需填写提示词。"
                else "每项输入等待对应提示词；超过 30 秒未匹配时停止任务。"
        }
        modeGroup.check(if (sendMode == InputSendMode.PROMPT) promptId else delayId)
        modeGroup.setOnCheckedChangeListener { _, id ->
            sendMode = if (id == promptId) InputSendMode.PROMPT else InputSendMode.DELAY
            updateSendMode()
        }
        updateSendMode()
        val sensitiveInput = CheckBox(this).apply { text = "包含敏感输入：隐藏回显并禁用输出日志"; isChecked = current?.sensitive ?: false }
        styledDialog(if (current == null) "新建输入方案" else "编辑输入方案", file.name, {
            column().apply {
                addView(text("方案名称", 13f, mutedColor())); addView(nameInput)
                addView(text("自动发送方式", 13f, mutedColor())); addView(modeGroup); addView(modeHint)
                addView(text("输入内容 · 每行一项", 13f, mutedColor())); addView(stepsInput)
                addView(promptFields)
                addView(sensitiveInput)
                addView(text("默认使用旧版延时发送。输入使用本机密钥加密保存。", 12f, mutedColor()))
            }
        }, { dialog -> listOf(
            "取消" to { dialog.dismiss() },
            "保存" to save@{
                val name = nameInput.text.toString().trim()
                val rawSteps = stepsInput.text.toString()
                if (name.isBlank()) { nameInput.error = "请填写方案名称"; nameInput.requestFocus(); return@save }
                if (rawSteps.isBlank()) { stepsInput.error = "请填写至少一项输入"; stepsInput.requestFocus(); return@save }
                val steps = rawSteps.split('\n').map { it.removeSuffix("\r") }
                val promptText = promptsInput.text.toString()
                val expected = if (promptText.isBlank()) emptyList() else promptText.split('\n').map { it.removeSuffix("\r") }
                if (sendMode == InputSendMode.PROMPT && (expected.size != steps.size || expected.any { it.isBlank() || it.length > 512 })) {
                    promptsInput.error = "提示词需与输入逐行对应，每项 1～512 个字符"; return@save
                }
                if (steps.size > 50 || steps.any { it.length > 4096 }) {
                    stepsInput.error = "最多 50 项，每项最多 4096 个字符"; stepsInput.requestFocus(); return@save
                }
                val updated = readInputPresets(file)
                if (updated.any { it.name.equals(name, true) && it.name != current?.name }) {
                    nameInput.error = "名称已存在，请使用另一个名称"; nameInput.requestFocus(); return@save
                }
                val target = updated.indexOfFirst { it.name == current?.name }
                val preset = ScriptInputPreset(name, steps, expected, sensitiveInput.isChecked, sendMode)
                if (target >= 0) updated[target] = preset else updated.add(preset)
                saveInputPresets(file, updated)
                if (current != null && current.name != name) updateShortcutPresetReferences(file, current.name, name)
                dialog.dismiss()
                if (onSaved != null) onSaved(preset) else showInputPresets()
            }
        ) })
    }

    private fun confirmPresetStep(preset: ScriptInputPreset, index: Int) {
        if (preset.sensitive) { message("敏感方案", "请通过快捷方式自动发送，执行时会隐藏敏感回显并禁用日志。"); return }
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
    private fun browserFontSize() = prefs.getInt("browserFontSize", 16).coerceIn(10, 20)

    private fun browserTransitionStyle() = BrowserTransitionStyle.fromKey(prefs.getString("browserTransitionStyle", null))

    private fun chooseBrowserTransitionStyle() {
        val current = browserTransitionStyle()
        styledDialog("目录过渡动画", "仅用于目录切换，选择后自动保存", { dialog ->
            column().apply {
                BrowserTransitionStyle.entries.forEach { style ->
                    addView(optionCard(if (style == BrowserTransitionStyle.FRESH) "${style.label} · 默认" else style.label,
                        style.description, style == current) {
                        prefs.edit().putString("browserTransitionStyle", style.key).apply()
                        dialog.dismiss()
                        showTab()
                    })
                }
                addView(text("关闭系统动画后，目录切换将直接完成。", 12f, mutedColor()))
            }
        }, { dialog -> listOf("取消" to { dialog.dismiss() }) })
    }

    private fun chooseBrowserFontSize() {
        val current = browserFontSize()
        styledDialog("文件管理字体大小", "调整左右两栏的文件、文件夹名称 · 下方为字号预览", { dialog ->
            column().apply {
                listOf(10, 12, 14, 16, 18, 20).forEach { size ->
                    addView(optionCard(if (size == 16) "$size · 默认" else "$size", "", size == current) {
                        prefs.edit().putInt("browserFontSize", size).apply()
                        // Pixel offsets no longer describe the same rows after a font change.
                        browserScrollPositions.clear()
                        dialog.dismiss()
                        showTab()
                    }.apply {
                        addView(text("📁  示例文件夹\n📄  示例脚本.sh", size.toFloat()).apply {
                            setPadding(0, dp(8), 0, 0)
                            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        })
                    })
                }
            }
        }, { dialog -> listOf("取消" to { dialog.dismiss() }) })
    }

    private fun settingsHeading(title: String) = text(title, 13f,
        if (dark) accent else Color.rgb(18, 112, 128)).apply {
        setPadding(dp(8), dp(8), dp(8), dp(10))
        typeface = Typeface.DEFAULT_BOLD
        if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
    }

    private fun settingsGroup() = column().apply {
        tag = "settingsGroup"
        setPadding(dp(1), dp(6), dp(1), dp(6))
        background = GradientDrawable().apply {
            setColor(if (dark) panel else Color.rgb(223, 232, 239))
            cornerRadius = dp(16).toFloat()
        }
        clipToOutline = true
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) }
    }

    private fun settingsRow(label: String, action: () -> Unit) = button(label, action).apply {
        setTextColor(android.content.res.ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(mutedColor(), this@MainActivity.foreground)))
        textSize = 14f
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(12))
        minHeight = dp(52)
        background = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(Color.argb(36, 40, 185, 207)), null,
            android.graphics.drawable.ColorDrawable(Color.WHITE))
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }

    private fun showAppAutomation() {
        val bindings = AppAutomation.bindings(prefs)
        val entries = bindings.entries.sortedBy { it.key }
        val status = text("正在读取模块状态…", 13f, mutedColor())
        val install = button("安装 / 更新配套模块") { showModuleInstaller() }
        val recent = getSharedPreferences("automation_status", MODE_PRIVATE)
        val recentTime = recent.getLong("time", 0)
        val recentLabel = if (recentTime > 0) java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(recentTime)) else ""
        styledDialog("App 自动执行", "同包名分身共用方案与冷却 · 运行中跳过", { dialog ->
            column().apply {
                addView(status)
                addView(install, LinearLayout.LayoutParams(-1, dp(50)).apply { bottomMargin = dp(8) })
                addView(text("最近结果 $recentLabel\n${recent.getString("message", "暂无执行记录")}", 13f))
                if (entries.isEmpty()) addView(text("先安装模块并添加绑定。", 14f))
                entries.forEach { entry ->
                    val policy = AppAutomation.policy(prefs, entry.key)
                    addView(optionCard(entry.value, "${entry.key}\n冷却 ${policy.cooldownSeconds} 秒 · 超时 ${policy.timeoutSeconds} 秒${if (policy.once) " · 单次启用" else ""}") {
                        dialog.dismiss()
                        AlertDialog.Builder(this@MainActivity).setTitle(entry.value)
                            .setItems(arrayOf("冷却 / 超时 / 单次启用", "重新启用并清除冷却", "删除绑定")) { _, item ->
                                when (item) {
                                    0 -> editAutomationPolicy(entry.key)
                                    1 -> { AppAutomation.rearm(prefs, entry.key); showAppAutomation() }
                                    2 -> saveAppAutomation(AppAutomation.bindings(prefs) - entry.key)
                                }
                            }.setNegativeButton("取消", null).show()
                    })
                }
            }
        }, { dialog -> listOf(
            "添加 / 修改" to { dialog.dismiss(); addAppAutomationBinding() },
            "重新同步" to { dialog.dismiss(); saveAppAutomation(bindings) },
            "关闭" to { dialog.dismiss() }
        ) })
        io.execute {
            val report = runCatching { AppAutomation.moduleStatus(root) }.getOrDefault("读取状态失败，请检查 Root 授权")
            runOnUiThread { if (!isDestroyed) {
                status.text = report
                install.text = if (report.contains("模块未安装")) "安装内置配套模块" else "安装 / 更新配套模块"
            } }
        }
    }

    private fun showModuleInstaller() {
        val info = text("正在读取内置模块…", 13f, mutedColor())
        val choices = column()
        val dialog = styledDialog("安装配套模块", "安装包已内置，无需另外下载", { column().apply {
            addView(info)
            addView(text("选择手机上的 Root 管理器继续安装。安装后按管理器提示重启，再回来同步 App 绑定。", 14f))
            addView(choices)
        } }, { panel -> listOf("仅保存 ZIP" to { panel.dismiss(); exportModule(null) }, "关闭" to { panel.dismiss() }) })
        appPickerIo.execute {
            val result = runCatching {
                val bundle = BundledModule.read(this)
                val managers = listOf("com.topjohnwu.magisk" to "Magisk（面具）", "me.weishu.kernelsu" to "KernelSU")
                    .filter { packageManager.getLaunchIntentForPackage(it.first) != null }
                bundle.version to managers
            }
            runOnUiThread {
                if (!isDestroyed && dialog.isShowing) {
                    result.fold({ (version, managers) ->
                        info.text = "内置模块 v$version · 配套当前 Tubing"
                        if (managers.isEmpty()) choices.addView(text("未找到 Magisk / KernelSU。可先保存 ZIP，再用你的 Root 管理器从本地安装。", 13f, mutedColor()))
                        managers.forEach { (pkg, name) -> choices.addView(optionCard("交给 $name 安装", "保存安装包并打开管理器") {
                            dialog.dismiss(); exportModule(pkg)
                        }) }
                    }, { info.text = "内置模块读取失败，请重新安装完整 APK。" })
                }
            }
        }
    }

    private fun exportModule(manager: String?) {
        if (exportingModule) return
        exportingModule = true
        appPickerIo.execute {
            val result = runCatching {
                val bundle = BundledModule.read(this)
                val uri = if (Build.VERSION.SDK_INT >= 29) BundledModule.exportDownloads(this, bundle) else null
                bundle.fileName to uri
            }
            runOnUiThread {
                exportingModule = false
                if (!isDestroyed) result.fold({ (name, uri) ->
                    if (uri == null) {
                        prefs.edit().putString("moduleExportManager", manager ?: "").apply()
                        try { startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("application/zip").putExtra(Intent.EXTRA_TITLE, name), 110) }
                        catch (_: Exception) { message("无法保存安装包", "请安装可用的系统文件管理器后重试。") }
                    } else openModuleManager(manager, uri, name)
                }, { message("模块导出失败", it.message ?: "无法保存安装包，请重试。") })
            }
        }
    }

    private fun openModuleManager(manager: String?, uri: Uri, name: String) {
        if (manager.isNullOrBlank()) {
            message("模块已保存", "安装包：$name\nAndroid 10 及以上位于 Download/Tubing；可在 Root 管理器的模块页面选择该 ZIP 安装。"); return
        }
        val intent = BundledModule.managerIntent(this, manager, uri)
        if (intent == null) { message("无法打开管理器", "安装包已保存，可手动打开 Root 管理器并选择 $name。"); return }
        try {
            startActivity(intent)
            if (intent.action != Intent.ACTION_VIEW)
                Toast.makeText(this, "请在管理器的模块页面选择安装包：$name（Download/Tubing）", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            val fallback = packageManager.getLaunchIntentForPackage(manager)
            if (fallback != null && runCatching { startActivity(fallback) }.isSuccess)
                Toast.makeText(this, "安装包已保存，请从模块页面选择 $name。", Toast.LENGTH_LONG).show()
            else message("无法打开管理器", "安装包已保存，请手动选择 $name 安装。")
        }
    }

    private fun editAutomationPolicy(pkg: String) {
        val policy = AppAutomation.policy(prefs, pkg)
        val cooldown = formInput("冷却秒数 10～86400", policy.cooldownSeconds.toString()).apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val timeout = formInput("任务超时秒数 5～3600", policy.timeoutSeconds.toString()).apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val once = CheckBox(this).apply { text = "单次启用：执行一次后暂停，需手动重新启用"; isChecked = policy.once }
        styledDialog("执行策略", pkg, { column().apply {
            addView(text("冷却时间（秒）", 13f)); addView(cooldown)
            addView(text("两次自动执行之间的最短间隔。例如 60 秒：启动后 60 秒内再次进入此 App 会跳过。冷却结束不会自动重跑，需离开后再进入。", 12f, mutedColor()))
            addView(text("任务超时（秒）", 13f)); addView(timeout)
            addView(text("单次任务允许运行的最长时间。例如 120 秒：超过 2 分钟仍未结束，会终止任务并记录超时；提前结束不受影响。", 12f, mutedColor()))
            addView(once)
        } }, { dialog -> listOf("取消" to { dialog.dismiss() }, "保存" to save@{
            val c = cooldown.text.toString().toIntOrNull()
            val t = timeout.text.toString().toIntOrNull()
            if (c == null || c !in 10..86400) { cooldown.error = "请输入 10～86400"; return@save }
            if (t == null || t !in 5..3600) { timeout.error = "请输入 5～3600"; return@save }
            AppAutomation.savePolicy(prefs, pkg, AutomationPolicy(c, t, once.isChecked))
            dialog.dismiss(); showAppAutomation()
        }) })
    }

    private fun addAppAutomationBinding() {
        if (quickShortcuts().isEmpty()) { message("暂无快捷方式", "请先在快捷方式中配置脚本与输入方案。"); return }
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val body = dialogPanel(maximumHeightDp = 620)
        body.addView(text("选择目标应用", 22f).apply { typeface = Typeface.DEFAULT_BOLD })
        body.addView(text("选择 App 绑定快捷方式，同包名分身共用", 13f, mutedColor()))
        val search = formInput("搜索应用名称或包名").apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        }
        body.addView(search)
        val count = text("正在读取已安装应用…", 12f, mutedColor())
        body.addView(count)
        val list = ListView(this).apply {
            divider = null
            clipToPadding = false
            setPadding(0, 0, 0, dp(4))
            isVerticalScrollBarEnabled = true
        }
        body.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        val empty = text("", 14f, mutedColor()).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        body.addView(empty)
        val footer = LinearLayout(this).apply {
            addView(button("手动填包名") { dialog.dismiss(); enterAutomationPackage() }, LinearLayout.LayoutParams(0, dp(50), 1f))
            addView(button("取消") { dialog.dismiss() }, LinearLayout.LayoutParams(0, dp(50), 1f))
        }
        body.addView(footer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        var allApps = emptyList<InstalledAutomationApp>()
        var filtered = allApps
        val bindings = AppAutomation.bindings(prefs)
        val adapter = object : BaseAdapter() {
            override fun getCount() = filtered.size
            override fun getItem(position: Int) = filtered[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val app = getItem(position)
                val row = convertView as? LinearLayout ?: LinearLayout(this@MainActivity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(76)
                    setPadding(dp(8), dp(10), dp(8), dp(10))
                    addView(ImageView(this@MainActivity).apply { scaleType = ImageView.ScaleType.FIT_CENTER },
                        LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) })
                    addView(column().apply {
                        addView(text("", 16f).apply { typeface = Typeface.DEFAULT_BOLD; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, 0, 0, dp(3)) })
                        addView(text("", 12f, mutedColor()).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE; setPadding(0, 0, 0, 0) })
                        addView(text("", 12f, accent).apply { setPadding(0, dp(3), 0, 0) })
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(text("›", 26f, accent).apply { setPadding(dp(8), 0, 0, 0) })
                }
                (row.getChildAt(0) as ImageView).setImageDrawable(app.icon)
                val labels = row.getChildAt(1) as LinearLayout
                (labels.getChildAt(0) as TextView).text = app.label
                (labels.getChildAt(1) as TextView).text = app.packageName
                (labels.getChildAt(2) as TextView).apply {
                    text = bindings[app.packageName]?.let { "已绑定 · $it" } ?: ""
                    visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
                }
                return row
            }
        }
        list.adapter = adapter
        fun filterApps() {
            val term = search.text.toString().trim()
            filtered = allApps.filter { term.isEmpty() || it.label.contains(term, true) || it.packageName.contains(term, true) }
            count.text = if (term.isEmpty()) "${allApps.size} 个应用 · 点击选择" else "找到 ${filtered.size} 个应用"
            empty.text = if (allApps.isEmpty()) "未读取到可打开的应用，可尝试手动填写包名。" else "没有匹配的应用，试试其他名称或包名。"
            empty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
            adapter.notifyDataSetChanged()
            list.setSelection(0)
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { filterApps() }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        list.setOnItemClickListener { _, _, position, _ ->
            val app = filtered[position]
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(search.windowToken, 0)
            dialog.dismiss()
            chooseAutomationShortcut(app.packageName, app.label)
        }
        dialog.setContentView(body)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        dialog.window?.setLayout(minOf(resources.displayMetrics.widthPixels - dp(32), dp(480)), minOf((resources.displayMetrics.heightPixels * 0.84f).toInt(), dp(620)))
        body.isFocusableInTouchMode = true; body.requestFocus()
        appPickerIo.execute {
            val result = runCatching {
                val collator = java.text.Collator.getInstance(java.util.Locale.getDefault())
                packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                    .distinctBy { it.activityInfo.packageName }.filter { it.activityInfo.packageName != packageName }
                    .mapNotNull { info -> runCatching {
                        val app = info.activityInfo.applicationInfo
                        InstalledAutomationApp(app.packageName, app.loadLabel(packageManager).toString(), app.loadIcon(packageManager))
                    }.getOrNull() }
                    .sortedWith(compareBy<InstalledAutomationApp> { if (bindings.containsKey(it.packageName)) 0 else 1 }
                        .thenComparator { a, b -> collator.compare(a.label, b.label) }.thenBy { it.packageName })
            }
            runOnUiThread {
                if (!isDestroyed && dialog.isShowing) {
                    allApps = result.getOrDefault(emptyList()); filterApps()
                    if (result.isFailure) { count.text = "读取应用失败"; empty.text = "可点击下方手动填包名，或稍后重试。" }
                }
            }
        }
    }

    private fun enterAutomationPackage() {
        val input = formInput("例如 com.example.app").apply { inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS }
        styledDialog("手动填写包名", "适用于应用列表中未显示的 App", { column().apply {
            addView(text("目标 App 包名", 13f, mutedColor())); addView(input)
        } }, { dialog -> listOf("返回列表" to { dialog.dismiss(); addAppAutomationBinding() }, "下一步" to next@{
            val pkg = input.text.toString().trim()
            if (!AppAutomation.validPackage(pkg) || pkg == packageName) { input.error = "请输入有效的其他 App 包名"; return@next }
            dialog.dismiss(); chooseAutomationShortcut(pkg, pkg)
        }) })
    }

    private fun chooseAutomationShortcut(pkg: String, label: String) {
        val shortcuts = quickShortcuts()
        if (shortcuts.isEmpty()) { message("暂无快捷方式", "请先创建快捷方式。"); return }
        val current = AppAutomation.bindings(prefs)[pkg]
        styledDialog("选择快捷方式", "$label\n$pkg", { dialog -> column().apply {
            shortcuts.forEach { shortcut ->
                addView(optionCard(shortcut.name, "${shortcut.fileName}\n输入方案 · ${shortcut.presetName ?: "不使用"}", current == shortcut.name) {
                    dialog.dismiss(); saveAppAutomation(AppAutomation.bindings(prefs) + (pkg to shortcut.name))
                })
            }
        } }, { dialog -> listOf("重选应用" to { dialog.dismiss(); addAppAutomationBinding() }, "取消" to { dialog.dismiss() }) })
    }

    private fun clearPrivateLogs() {
        confirmAction("清理日志与记忆输入", "清除终端显示、已保存输出日志及记忆输入；保留脚本和输入方案。运行中的任务请先停止。", "清理") {
            if (SessionState.running) { message("任务运行中", "请先停止任务再清理。"); return@confirmAction }
            io.execute {
                val result = runCatching {
                    val cleaned = root.pruneLogs(clear = true)
                    check(cleaned.code == 0) { "Root 日志清理失败" }
                    cacheDir.listFiles()?.filter { it.name.matches(Regex("[a-f0-9-]+\\.log")) }?.forEach { check(it.delete()) }
                    val editor = prefs.edit()
                    prefs.all.keys.filter { it.startsWith("scriptInput_history_") }.forEach { editor.remove(it) }
                    check(editor.commit())
                    SessionState.clear()
                }
                runOnUiThread { if (!isDestroyed) message(if (result.isSuccess) "已清理" else "部分清理未完成", result.exceptionOrNull()?.message ?: "日志与记忆输入已清理，方案已保留。") }
            }
        }
    }

    private fun saveAppAutomation(bindings: Map<String, String>) {
        io.execute {
            val saved = runCatching {
                // Persist first: stale module configuration cannot execute a removed binding.
                AppAutomation.save(prefs, bindings)
                val result = AppAutomation.sync(root, bindings, packageName)
                check(result.code == 0) { result.output.ifBlank { "Root 配置写入失败" } }
            }
            runOnUiThread {
                if (!isDestroyed) {
                    saved.fold({ Toast.makeText(this, "绑定已同步，模块将在下次切入 App 时执行", Toast.LENGTH_LONG).show(); showAppAutomation() },
                        { message("模块配置同步失败", "${it.message}\n绑定已保存在 App 内；获得 Root 后点击重新同步。") })
                }
            }
        }
    }

    private fun settingsPage() {
        val area = column()
        area.addView(text("设置", 25f).apply { setPadding(dp(4), dp(8), dp(4), dp(16)) })
        fun addGroup(title: String) = settingsGroup().also { group ->
            area.addView(settingsHeading(title)); area.addView(group)
        }
        val moduleGroup = addGroup("面具模块")
        val displayGroup = addGroup("显示与外观")
        val fileGroup = addGroup("文件管理")
        val executionGroup = addGroup("终端与执行")
        val historyGroup = addGroup("日志与记录")
        val deviceGroup = addGroup("设备与维护")
        val projectGroup = addGroup("反馈与项目")
        executionGroup.addView(Switch(this).apply {
            text = "快捷执行后自动关闭 App 后台"
            setTextColor(this@MainActivity.foreground)
            setPadding(dp(16), dp(12), dp(16), dp(12)); minimumHeight = dp(52)
            isChecked = prefs.getBoolean(QuickExecutionExit.SETTING, false)
            setOnCheckedChangeListener { _, enabled ->
                prefs.edit().putBoolean(QuickExecutionExit.SETTING, enabled).apply()
                if (!enabled) QuickExecutionExit.cancel()
            }
        })
        executionGroup.addView(text("默认关闭。开启后，Start 或模块绑定的快捷任务结束（含失败、终止）并保存记录后，移除最近任务并结束 Tubing 后台。执行期间保持运行，普通执行不受影响。", 12f, mutedColor()))
        deviceGroup.addView(settingsRow("自定义包名") { showCustomPackage() })
        moduleGroup.addView(settingsRow("App 自动执行") { showAppAutomation() })
        fileGroup.addView(settingsRow("默认导入方式 · ${ImportSource.fromKey(prefs.getString("importSource", "ask")).label}") { chooseDefaultImportSource() })
        val quickHome = prefs.getBoolean("homeDefaultQuickInterface", prefs.getBoolean("quickInterface", false))
        displayGroup.addView(settingsRow("首页默认 · ${if (quickHome) "快捷版" else "完整版"}") {
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
        executionGroup.addView(settingsRow("默认记忆输入 · ${if (rememberByDefault) "开" else "关"}") {
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
        modeSelector = settingsRow(modeText()) { chooseMode() }
        executionGroup.addView(modeSelector)
        displayGroup.addView(settingsRow("主题 · " + when (prefs.getString("theme", "system")) { "dark" -> "深色"; "light" -> "浅色"; else -> "跟随系统" }) {
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
        executionGroup.addView(settingsRow("终端字体 · ${prefs.getInt("fontSize", 14)}") {
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
        fileGroup.addView(settingsRow("文件管理字体大小 · ${browserFontSize()}") { chooseBrowserFontSize() })
        fileGroup.addView(settingsRow("目录过渡动画 · ${browserTransitionStyle().label}") { chooseBrowserTransitionStyle() })
        historyGroup.addView(Switch(this).apply {
            text = "保存输出日志（敏感方案不保存）"; setTextColor(this@MainActivity.foreground); isChecked = prefs.getBoolean("saveLogs", false)
            setPadding(dp(16), dp(12), dp(16), dp(12)); minimumHeight = dp(52)
            setOnCheckedChangeListener { _, enabled -> prefs.edit().putBoolean("saveLogs", enabled).apply() }
        })
        historyGroup.addView(text("输出日志默认关闭；开启后单次最多约 512KB，保留 7 天、最多 20 份。", 12f, mutedColor()))
        executionGroup.addView(settingsRow("普通任务超时 · ${prefs.getInt("executionTimeoutSeconds", 120)} 秒") {
            val value = formInput("5～3600 秒", prefs.getInt("executionTimeoutSeconds", 120).toString()).apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }
            styledDialog("普通任务超时", "自动绑定可在各自执行策略中单独设置", { value }, { dialog -> listOf(
                "取消" to { dialog.dismiss() }, "保存" to save@{
                    val seconds = value.text.toString().toIntOrNull()
                    if (seconds == null || seconds !in 5..3600) { value.error = "请输入 5～3600"; return@save }
                    prefs.edit().putInt("executionTimeoutSeconds", seconds).apply(); dialog.dismiss(); showTab()
                }
            ) })
        })
        historyGroup.addView(settingsRow("执行历史保留 · ${ExecutionHistory.limit(this)} 次") { editExecutionHistoryLimit() })
        historyGroup.addView(settingsRow("查看执行日志") { work({ root.logs() }) { showExecutionLogs(it.output) } })
        historyGroup.addView(settingsRow("清理日志与记忆输入") { clearPrivateLogs() })
        deviceGroup.addView(text("Root 状态 · 已授权", 14f).apply { setPadding(dp(16), dp(12), dp(16), dp(12)) })
        deviceGroup.addView(settingsRow("重新检查 Root") { checkRoot() })
        deviceGroup.addView(settingsRow("清理临时文件") {
            if (SessionState.running) message("暂时无法清理", "请在执行结束后清理临时文件。")
            else work({ root.cleanTmp() }) { message("清理临时文件", if (it.code == 0) "清理完成" else it.output) }
        })
        checkUpdateButton = settingsRow(if (checkingUpdate) "正在检查更新…" else "检查更新") { checkForUpdates() }.apply {
            isEnabled = !checkingUpdate
        }
        projectGroup.addView(checkUpdateButton)
        projectGroup.addView(settingsRow("反馈频道 · Telegram") { openExternalLink("https://t.me/mbazhuan") })
        projectGroup.addView(settingsRow("GitHub 仓库") { openExternalLink("https://github.com/givsk983/tubing") })
        projectGroup.addView(settingsRow("旧 GitHub 仓库（账号申诉中）") { openExternalLink("https://github.com/yunhe4550-design/Tubing") })
        area.addView(text("Tubing ${packageManager.getPackageInfo(packageName, 0).versionName}\nRoot 交互终端", 12f, mutedColor()).apply { gravity = Gravity.CENTER })
        content.addView(ScrollView(this).apply { addView(area) }, FrameLayout.LayoutParams(-1, -1))
    }
    private fun showCustomPackage() {
        if (creatingPackage) { message("正在处理", "请等待当前副本生成或安装完成。"); return }
        if (SessionState.running || SessionState.startGate.isPreparing()) {
            message("请先结束任务", "执行结束后再生成自定义包名副本。"); return
        }
        val input = formInput("例如 com.example.tubing", prefs.getString("customPackageName", "") ?: "").apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setSingleLine(true)
        }
        styledDialog("自定义包名", "当前包名：$packageName", { column().apply {
            addView(text("生成并安装独立副本，原应用保留。新副本需要重新授权 Root，设置、输入方案和快捷方式需重新配置；脚本库仍共用。自动执行需在副本中重新绑定并同步，模块请更新至内置版本；最后同步的应用负责自动执行。", 13f))
            addView(input)
            addView(text("包名至少两段，以点分隔，每段以英文字母开头，仅含字母、数字和下划线。已有包名不会覆盖。副本使用本机生成的签名，后续官方 APK 无法直接覆盖副本。", 12f, mutedColor()))
        } }, { dialog -> listOf("取消" to { dialog.dismiss() }, "生成副本" to generate@{
            val target = input.text.toString().trim()
            if (!PackageManifest.validName(target) || target == packageName) {
                input.error = "请输入与当前应用不同的有效包名（最多 200 字符）"; return@generate
            }
            prefs.edit().putString("customPackageName", target).apply()
            dialog.dismiss()
            creatingPackage = true
            Toast.makeText(this, "正在生成并校验副本…", Toast.LENGTH_LONG).show()
            appPickerIo.execute {
                val result = runCatching { CustomPackage.create(this, target) }
                runOnUiThread {
                    creatingPackage = false
                    if (isDestroyed || isFinishing) { result.getOrNull()?.delete(); return@runOnUiThread }
                    result.fold({ apk ->
                        styledDialog("副本已生成", target, { text("点击安装后通过 Root 安装独立副本，原应用与数据保留。") },
                            { confirm -> listOf("取消" to { apk.delete(); confirm.dismiss() }, "安装副本" to {
                                confirm.dismiss(); creatingPackage = true
                                Toast.makeText(this, "正在安装副本…", Toast.LENGTH_LONG).show()
                                appPickerIo.execute {
                                    val installed = runCatching { CustomPackage.install(root, apk, target) }
                                    apk.delete()
                                    runOnUiThread {
                                        creatingPackage = false
                                        if (!isDestroyed && !isFinishing) installed.fold({ report ->
                                            if (report.code == 0 && report.output.lineSequence().any { it.trim() == "Success" }) {
                                                styledDialog("安装成功", target, { text("可从桌面打开副本，或点击下方按钮。请为副本重新授权 Root。") },
                                                    { done -> listOf("完成" to { done.dismiss() }, "打开副本" to {
                                                        done.dismiss()
                                                        runCatching { startActivity(Intent().setComponent(android.content.ComponentName(target, MainActivity::class.java.name))) }
                                                            .onFailure { message("打开失败", "请从桌面打开新安装的副本。") }
                                                    }) })
                                            } else message("安装未完成", report.output.ifBlank { "Root 不可用或安装超时，请检查手机已安装的应用后重试。" })
                                        }, { message("安装失败", it.message ?: "请检查 Root 授权后重试") })
                                    }
                                }
                            }) }).apply { setOnCancelListener { apk.delete() } }
                    }, { message("生成失败", it.message ?: "无法生成安装包，请重试") })
                }
            }
        }) })
    }

    private fun checkForUpdates() {
        if (checkingUpdate) return
        checkingUpdate = true
        checkUpdateButton?.apply { text = "正在检查更新…"; isEnabled = false }
        val installed = packageManager.getPackageInfo(packageName, 0).versionName ?: "1.4.4"
        updateIo.execute {
            val result = runCatching { GitHubUpdateChecker.check() }
            runOnUiThread {
                checkingUpdate = false
                if (isDestroyed || isFinishing) return@runOnUiThread
                checkUpdateButton?.apply { text = "检查更新"; isEnabled = true }
                result.fold(onSuccess = { release ->
                    if (release == null) {
                        message("暂无可用版本", "GitHub 暂无可识别的已发布版本。当前版本：$installed")
                    } else if (!AppUpdates.isNewer(release, installed)) {
                        message("暂无新版本", "当前版本：$installed\nGitHub 最新版本：${release.tag}${if (release.prerelease) "（测试版）" else ""}")
                    } else {
                        styledDialog("发现新版本 ${release.tag}", "当前版本 $installed${if (release.prerelease) " · 测试版更新" else ""}", {
                            column().apply {
                                addView(text(release.title, 17f).apply { typeface = Typeface.DEFAULT_BOLD })
                                addView(text(release.notes.ifBlank { "此版本暂未提供更新说明。" }, 14f, mutedColor()).apply {
                                    setTextIsSelectable(true)
                                })
                                addView(button("查看 GitHub 发布页") { openExternalLink(release.pageUrl) }, LinearLayout.LayoutParams(-1, dp(50)))
                            }
                        }, { dialog -> listOf(
                            "稍后" to { dialog.dismiss() },
                            (if (release.apkUrl != null) "下载 APK" else "前往发布页") to {
                                dialog.dismiss()
                                openExternalLink(release.apkUrl ?: release.pageUrl)
                            }
                        ) })
                    }
                }, onFailure = { error ->
                    val reason = when (error) {
                        is java.net.SocketTimeoutException -> "连接 GitHub 超时，请检查网络后重试。"
                        is java.io.IOException -> "无法连接 GitHub，请检查网络后重试。"
                        is org.json.JSONException -> "GitHub 返回的数据无法读取，请稍后重试。"
                        else -> error.message ?: "检查失败，请稍后重试。"
                    }
                    styledDialog("检查更新失败", "", { text(reason, 14f) }, { dialog -> listOf(
                        "关闭" to { dialog.dismiss() },
                        "查看发布页" to { dialog.dismiss(); openExternalLink(AppUpdates.RELEASES_URL) }
                    ) })
                })
            }
        }
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
        if (requestCode == 110) {
            val manager = prefs.getString("moduleExportManager", null)?.takeIf { it.isNotBlank() }
            prefs.edit().remove("moduleExportManager").apply()
            val uri = data?.data
            if (resultCode == RESULT_OK && uri != null) {
                appPickerIo.execute {
                    val result = runCatching {
                        val bundle = BundledModule.read(this)
                        (contentResolver.openOutputStream(uri, "wt") ?: error("无法写入安装包")).use { it.write(bundle.bytes) }
                        bundle.fileName
                    }
                    runOnUiThread { if (!isDestroyed) result.fold({ openModuleManager(manager, uri, it) }, { message("模块保存失败", it.message ?: "请重试。") }) }
                }
            }
            return
        }
        if (requestCode !in 100..102 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val name = runCatching { displayName(uri) }.getOrElse {
            message("导入失败", it.message ?: "无法读取文件名"); return
        }
        if (requestCode == 102 || name.endsWith(".zip", true) ||
            runCatching { contentResolver.getType(uri) }.getOrNull() in setOf("application/zip", "application/x-zip-compressed")) {
            openZipImport(uri, name)
            return
        }
        work({
            val temp = File.createTempFile("incoming-", ".tmp", cacheDir)
            try {
                (contentResolver.openInputStream(uri) ?: error("无法读取文件")).use { input -> temp.outputStream().use { input.copyTo(it) } }
                val magic = temp.inputStream().use { input -> ByteArray(4).also { input.read(it) } }
                val kind = ZipExtractor.importKind(name, magic)
                root.import(temp, name, kind).also { check(it.code == 0) { it.output } }
            } finally { temp.delete() }
        }) { result ->
            val fields = result.output.lineSequence().firstOrNull { it.startsWith("IMPORTED|") }?.split('|')
            if (fields?.size == 3) selected = ManagedFile(fields[1], fields[2], "777")
            tab = 0; showTab(); message("导入完成", selected?.name ?: "文件已导入")
        }
    }
    private fun displayName(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "未命名"
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
        favoritesSheet?.takeIf { it.isShowing }?.let { it.dismiss(); return }
        if (!granted) { super.onBackPressed(); return }
        if (tab == 1) {
            lastBackPressedAt = 0L
            if (!goParent(activeBrowserLeft)) Toast.makeText(this, "已在根目录", Toast.LENGTH_SHORT).show()
            return
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastBackPressedAt <= 2000L) finishAndRemoveTask()
        else {
            lastBackPressedAt = now
            Toast.makeText(this, "再按一次返回键退出", Toast.LENGTH_SHORT).show()
        }
    }
    override fun onDestroy() {
        favoritesSheet?.closeImmediately()
        browserRequests.invalidateAll()
        browserReadTasks.forEach { it?.cancel(false) }
        browserIo.shutdown()
        startButtonHandler.removeCallbacks(startButtonRefresh)
        SessionState.unsubscribe(listener); terminalReady = false; terminal?.destroy()
        io.shutdown(); updateIo.shutdownNow(); appPickerIo.shutdownNow(); super.onDestroy()
    }
}
