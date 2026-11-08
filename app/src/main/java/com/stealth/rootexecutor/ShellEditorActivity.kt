package com.stealth.rootexecutor

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/** A separate, non-exported screen gives the editor its own keyboard and back navigation. */
class ShellEditorActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private val store = ShellScriptStore()
    private var document: ShellScriptDocument? = null
    private lateinit var editor: ShellCodeEditText
    private lateinit var heading: TextView
    private lateinit var status: TextView
    private lateinit var pathLabel: TextView
    private lateinit var saveButton: Button
    private lateinit var session: File
    private var busy = false
    private var lastBackup: String? = null
    private var message = "正在读取脚本…"
    private var source = ""
    private var exitHeld = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val theme = getSharedPreferences("settings", MODE_PRIVATE).getString("theme", "system")
        val dark = theme == "dark" || (theme != "light" &&
            resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
        setTheme(if (dark) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar)
        super.onCreate(savedInstanceState)
        source = intent.getStringExtra("path").orEmpty()
        if (source.isEmpty()) { finish(); return }
        QuickExecutionExit.editorOpened(); exitHeld = true
        val token = savedInstanceState?.getString("session")?.takeIf { it.matches(Regex("[a-f0-9-]{36}")) }
            ?: UUID.randomUUID().toString()
        session = File(cacheDir, "sh-editor-$token").apply { mkdirs() }
        val palette = UiPalette.forTheme(dark)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.background)
        }
        fun label(size: Float, color: Int = palette.text) = TextView(this).apply {
            textSize = size; setTextColor(color)
        }
        fun button(value: String, action: () -> Unit) = Button(this).apply {
            text = value; isAllCaps = false; textSize = 13f; setTextColor(palette.accent)
            setOnClickListener { action() }
        }
        val toolbar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        toolbar.addView(button("返回") { leave() }, LinearLayout.LayoutParams(dp(72), dp(52)))
        heading = label(19f).apply { text = "SH 编辑器"; typeface = Typeface.DEFAULT_BOLD }
        toolbar.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        saveButton = button("保存") { if (document == null) load() else save() }.apply { isEnabled = false }
        toolbar.addView(saveButton, LinearLayout.LayoutParams(dp(80), dp(52)))
        body.addView(toolbar)
        pathLabel = label(12f, palette.muted).apply {
            text = source; maxLines = 2; ellipsize = TextUtils.TruncateAt.MIDDLE
            setPadding(dp(12), dp(4), dp(12), dp(10)); setTextIsSelectable(true)
        }
        body.addView(pathLabel)
        editor = ShellCodeEditText(this, dark).apply { isEnabled = false }
        body.addView(editor, LinearLayout.LayoutParams(-1, 0, 1f))
        val keys = LinearLayout(this)
        keys.addView(button("撤销") { editor.onTextContextMenuItem(android.R.id.undo) })
        keys.addView(button("重做") { editor.onTextContextMenuItem(android.R.id.redo) })
        listOf("Tab" to "\t", "/" to "/", "$" to "$", "'" to "'", "\"" to "\"", "|" to "|", "&" to "&", "(" to "(", ")" to ")").forEach { (name, value) ->
            keys.addView(button(name) {
                if (editor.isEnabled) {
                    val start = editor.selectionStart.coerceAtLeast(0)
                    val end = editor.selectionEnd.coerceAtLeast(0)
                    editor.text?.replace(minOf(start, end), maxOf(start, end), value)
                    editor.requestFocus()
                }
            }, LinearLayout.LayoutParams(dp(58), dp(46)))
        }
        body.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(keys) })
        status = label(12f, palette.muted).apply {
            setPadding(dp(12), dp(8), dp(12), dp(10))
            setOnClickListener { lastBackup?.let { showBackup(it) } }
        }
        body.addView(status)
        setContentView(body)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        body.setOnApplyWindowInsetsListener { view, insets ->
            val compat = WindowInsetsCompat.toWindowInsetsCompat(insets, view)
            val bars = compat.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val keyboard = compat.getInsets(WindowInsetsCompat.Type.ime()).bottom
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard))
            insets
        }
        body.requestApplyInsets()
        editor.onDocumentChanged = { refreshStatus() }
        editor.onCursorChanged = { refreshStatus() }
        if (Build.VERSION.SDK_INT >= 33) onBackInvokedDispatcher.registerOnBackInvokedCallback(
            android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { leave() }
        if (savedInstanceState?.getBoolean("loaded") == true) restore(savedInstanceState) else load()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun dirty() = document?.let { editor.text.toString() != it.text } == true

    private fun refreshStatus() {
        if (!::status.isInitialized) return
        val doc = document
        heading.text = if (dirty()) "SH 编辑器 · 未保存" else "SH 编辑器"
        saveButton.text = if (doc == null && !busy) "重试" else if (busy && doc != null) "保存中" else "保存"
        saveButton.isEnabled = !busy && (doc == null || dirty())
        val value = editor.text.toString()
        val cursor = editor.selectionStart.coerceIn(0, value.length)
        val line = 1 + value.take(cursor).count { it == '\n' }
        val column = cursor - value.lastIndexOf('\n', (cursor - 1).coerceAtLeast(-1))
        val encoding = "UTF-8${if (doc?.bom == true) " BOM" else ""} · ${if (doc?.crlf == true) "CRLF" else "LF"}"
        status.text = if (doc == null) message else "行 $line · 列 $column · $encoding\n" +
            if (busy) "正在备份并保存…" else if (dirty()) "有未保存的修改 · 保存前自动备份" else message
        status.contentDescription = status.text.toString() + if (lastBackup != null) "，点按查看备份位置" else ""
    }

    private fun load() {
        if (busy) return
        busy = true; message = "正在读取脚本…"; refreshStatus()
        io.execute {
            val result = runCatching { store.read(source) }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                busy = false
                result.fold({ doc ->
                    document = doc; pathLabel.text = doc.path
                    editor.setText(doc.text); editor.setSelection(0); editor.isEnabled = true
                    message = "未修改 · 保存前自动备份"
                }, { error ->
                    message = error.message ?: "无法读取脚本，请重试"
                    AlertDialog.Builder(this).setTitle("无法编辑").setMessage(message)
                        .setPositiveButton("关闭", null).show()
                })
                refreshStatus()
            }
        }
    }

    private fun save(closeAfter: Boolean = false) {
        val doc = document ?: return
        if (busy) return
        if (!dirty()) { if (closeAfter) finish(); return }
        if (SessionState.running || SessionState.startGate.isPreparing()) {
            Toast.makeText(this, "请等待当前执行任务结束后再保存", Toast.LENGTH_LONG).show(); return
        }
        val value = editor.text.toString()
        busy = true; editor.isEnabled = false; refreshStatus()
        io.execute {
            val result = runCatching { store.save(doc, value, cacheDir) }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                busy = false; editor.isEnabled = true
                result.fold({ saved ->
                    document = saved.document; lastBackup = saved.backupPath
                    message = "已保存 · 点此查看备份位置"
                    setResult(RESULT_OK)
                    Toast.makeText(this, "已保存，原文件已备份", Toast.LENGTH_SHORT).show()
                    if (closeAfter) finish()
                }, { error ->
                    message = "保存失败，草稿已保留"
                    AlertDialog.Builder(this).setTitle("保存失败").setMessage(error.message ?: message)
                        .setPositiveButton("继续编辑", null).show()
                })
                refreshStatus()
            }
        }
    }

    private fun showBackup(path: String) {
        AlertDialog.Builder(this).setTitle("原文件备份")
            .setMessage("$path\n\n每次保存会在脚本同目录创建独立备份，可在文件管理中复制或重命名后恢复。备份不会自动清理。")
            .setNeutralButton("复制路径") { _, _ ->
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("脚本备份路径", path))
            }.setPositiveButton("关闭", null).show()
    }

    private fun leave() {
        if (busy && document != null) { Toast.makeText(this, "正在保存，请稍候", Toast.LENGTH_SHORT).show(); return }
        if (!dirty()) { finish(); return }
        AlertDialog.Builder(this).setTitle("保存修改？").setMessage("离开前是否保存脚本？保存时会先备份原文件。")
            .setNegativeButton("继续编辑", null)
            .setNeutralButton("放弃修改") { _, _ -> finish() }
            .setPositiveButton("保存并返回") { _, _ -> save(closeAfter = true) }.show()
    }

    @Deprecated("Handled by the back dispatcher on Android 13+")
    override fun onBackPressed() = leave()

    private fun persistDraft(): Boolean = runCatching {
        val doc = document ?: return false
        File(session, "original").writeBytes(doc.encode(doc.text))
        File(session, "draft").writeText(editor.text.toString(), Charsets.UTF_8)
        true
    }.getOrDefault(false)

    private fun restore(state: Bundle) {
        runCatching {
            val doc = ShellScriptDocument.decode(state.getString("documentPath")!!,
                File(session, "original").readBytes(), state.getString("fingerprint")!!)
            val draft = File(session, "draft").readText(Charsets.UTF_8)
            document = doc; pathLabel.text = doc.path
            editor.setText(draft)
            editor.setSelection(state.getInt("cursor").coerceIn(0, draft.length))
            editor.isEnabled = true
            lastBackup = state.getString("backup")
            message = "已恢复编辑内容 · 保存前自动备份"
        }.onFailure {
            message = "草稿缓存无法恢复，请返回后重新打开脚本"
            // Do not silently replace an unrecoverable draft with the current disk contents.
            saveButton.setOnClickListener { leave() }
        }
        refreshStatus()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::session.isInitialized) {
            outState.putString("session", session.name.removePrefix("sh-editor-"))
            if (document != null && persistDraft()) {
                outState.putBoolean("loaded", true)
                outState.putString("documentPath", document!!.path)
                outState.putString("fingerprint", document!!.fingerprint)
                outState.putString("backup", lastBackup)
                outState.putInt("cursor", editor.selectionStart)
            }
        }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (exitHeld) QuickExecutionExit.editorClosed()
        if (::editor.isInitialized) editor.release()
        io.shutdown()
        if (isFinishing && ::session.isInitialized) session.deleteRecursively()
        super.onDestroy()
    }
}
