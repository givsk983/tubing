package com.stealth.rootexecutor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.InputFilter
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.Toast
import java.util.concurrent.Executors

/** Native multiline input keeps IME, selection, clipboard and platform undo behavior. */
internal class ShellCodeEditText(context: Context, private val dark: Boolean) : EditText(context) {
    private val palette = UiPalette.forTheme(dark)
    private val gutterPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var revision = 0
    private var gutter = 0
    private var closed = false
    var onDocumentChanged: (() -> Unit)? = null
    var onCursorChanged: (() -> Unit)? = null
    private class SyntaxColor(color: Int) : ForegroundColorSpan(color)
    private val highlight = Runnable {
        val snapshot = text.toString()
        val version = revision
        worker.execute {
            val tokens = ShellSyntax.tokens(snapshot)
            handler.post {
                if (!closed && version == revision) {
                    val editable = text ?: return@post
                    editable.getSpans(0, editable.length, SyntaxColor::class.java).forEach { editable.removeSpan(it) }
                    tokens.forEach { editable.setSpan(SyntaxColor(tokenColor(it.kind)), it.start, it.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                }
            }
        }
    }

    init {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        typeface = Typeface.MONOSPACE
        textSize = 14f
        gravity = Gravity.TOP or Gravity.START
        textDirection = TEXT_DIRECTION_LTR
        layoutDirection = LAYOUT_DIRECTION_LTR
        setHorizontallyScrolling(true)
        setTextColor(palette.text)
        setBackgroundColor(palette.surface)
        setHintTextColor(palette.muted)
        setLineSpacing(0f, 1.15f)
        includeFontPadding = false
        isVerticalScrollBarEnabled = true
        isHorizontalScrollBarEnabled = true
        isSaveEnabled = false // Large drafts live in private cache, outside Binder's state limit.
        filters = arrayOf(InputFilter { source, start, end, dest, dstart, dend ->
            if (dest.length - (dend - dstart) + (end - start) > ShellScriptDocument.MAX_BYTES) {
                Toast.makeText(context, "内容过长，此次输入未插入（最多 256K 字符）", Toast.LENGTH_SHORT).show()
                dest.subSequence(dstart, dend)
            } else null
        })
        contentDescription = "SH 脚本编辑区域"
        updateGutter()
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                revision++
                updateGutter()
                handler.removeCallbacks(highlight)
                if (!closed) handler.postDelayed(highlight, 180)
                onDocumentChanged?.invoke()
            }
        })
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun updateGutter() {
        val digits = (1 + (text?.count { it == '\n' } ?: 0)).toString().length.coerceAtLeast(2)
        val next = paint.measureText("0".repeat(digits)).toInt() + dp(20)
        if (next != gutter) {
            gutter = next
            setPadding(gutter + dp(10), dp(12), dp(16), dp(16))
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val saved = canvas.save()
        canvas.clipRect(scrollX + gutter, scrollY, scrollX + width, scrollY + height)
        super.onDraw(canvas)
        canvas.restoreToCount(saved)
        gutterPaint.color = palette.background
        canvas.drawRect(scrollX.toFloat(), scrollY.toFloat(), (scrollX + gutter).toFloat(), (scrollY + height).toFloat(), gutterPaint)
        val lines = layout ?: return
        gutterPaint.typeface = typeface
        gutterPaint.textSize = textSize
        gutterPaint.textAlign = Paint.Align.RIGHT
        val first = lines.getLineForVertical((scrollY - totalPaddingTop).coerceAtLeast(0))
        val last = lines.getLineForVertical((scrollY + height - totalPaddingTop).coerceAtLeast(0))
        val current = lines.getLineForOffset(selectionStart.coerceIn(0, text?.length ?: 0))
        for (line in first..last) {
            gutterPaint.color = if (line == current) palette.accent else palette.muted
            canvas.drawText((line + 1).toString(), (scrollX + gutter - dp(8)).toFloat(),
                (lines.getLineBaseline(line) + totalPaddingTop).toFloat(), gutterPaint)
        }
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        invalidate()
        onCursorChanged?.invoke()
    }

    private fun tokenColor(kind: ShellTokenKind): Int = android.graphics.Color.parseColor(when (kind) {
        ShellTokenKind.COMMENT -> if (dark) "#94A3A3" else "#596B60"
        ShellTokenKind.STRING -> if (dark) "#D9CB8C" else "#795500"
        ShellTokenKind.VARIABLE -> if (dark) "#85C9F4" else "#145F91"
        ShellTokenKind.KEYWORD -> if (dark) "#C4ABED" else "#7343A3"
        ShellTokenKind.NUMBER -> if (dark) "#F0B991" else "#985117"
        ShellTokenKind.OPERATOR -> if (dark) "#69D8CA" else "#006D64"
    })

    fun release() {
        closed = true
        handler.removeCallbacksAndMessages(null)
        worker.shutdownNow()
    }
}
