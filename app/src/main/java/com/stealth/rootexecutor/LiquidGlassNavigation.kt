package com.stealth.rootexecutor

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import com.example.liquidglass.LiquidGlassTabBar
import com.example.liquidglass.LiquidGlassView

/** App labels/icons only; glass, capture, droplet and gestures are the upstream widget. */
internal class LiquidGlassNavigation(context: Context, dark: Boolean, onSelect: (Int) -> Unit) : LiquidGlassTabBar(context) {
    private val palette = UiPalette.forTheme(dark)
    private fun d(value: Float) = value * resources.displayMetrics.density
    init {
        // Keep optical tint independent from the label appearance: dark glass
        // uses the upstream black scrim, while light glass keeps a white highlight.
        overLight = dark
        glassTint = if (dark) Color.argb(28, 8, 18, 20) else Color.TRANSPARENT
        edgeHighlightOpacity = if (dark) 35f else 100f
        enableDynamicBackground = true
        enableAdaptiveTint = false
        blurAmount = 0.025f
        saturation = 110f
        cornerRadius = d(38f)
        // Let the outer capsule follow the tab/droplet's measured height.
        setPadding(d(4f).toInt(), d(2f).toInt(), d(4f).toInt(), d(2f).toInt())
        selectedTintColor = palette.accent
        setTabs(listOf("文件执行", "文件管理", "设置", "关于").mapIndexed { index, title ->
            TabItem(title, NavIcon(index, palette.accent))
        })
        onAppearanceChanged(!dark)
        for (i in 0 until childCount) (getChildAt(i) as? LiquidGlassView)?.apply {
            overLight = dark
            enableAdaptiveTint = false
            edgeHighlightOpacity = if (dark) 45f else 100f
            glassTint = if (dark) Color.argb(20, 35, 94, 83) else Color.TRANSPARENT
        }
        onTabSelected = onSelect
    }
    fun select(index: Int) {
        if (selectedIndex == index) return
        val callback = onTabSelected
        onTabSelected = null
        selectedIndex = index
        onTabSelected = callback
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // A drag belongs to the glass bar for its entire lifetime, even when
        // the finger drifts upward into the file manager's favorites region.
        if (event.actionMasked == MotionEvent.ACTION_DOWN)
            parent?.requestDisallowInterceptTouchEvent(true)
        val handled = super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL)
            parent?.requestDisallowInterceptTouchEvent(false)
        return handled
    }

    private inner class NavIcon(private val index: Int, private val tint: Int) : Drawable() {
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = tint; style = Paint.Style.STROKE; strokeWidth = 1.7f
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        override fun getIntrinsicWidth() = d(21f).toInt()
        override fun getIntrinsicHeight() = d(21f).toInt()
        override fun draw(canvas: Canvas) {
            canvas.save(); canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
            canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
            when (index) {
                0 -> { canvas.drawRoundRect(3f, 4f, 21f, 20f, 4f, 4f, stroke)
                    val p = Path().apply { moveTo(7f, 9f); lineTo(10f, 12f); lineTo(7f, 15f) }
                    canvas.drawPath(p, stroke); canvas.drawLine(13f, 15f, 17f, 15f, stroke) }
                1 -> { val p = Path().apply { moveTo(3f, 8f); lineTo(3f, 6f); quadTo(3f, 4f, 5f, 4f)
                    lineTo(10f, 4f); lineTo(12f, 7f); lineTo(19f, 7f); quadTo(21f, 7f, 21f, 9f)
                    lineTo(21f, 18f); quadTo(21f, 20f, 19f, 20f); lineTo(5f, 20f); quadTo(3f, 20f, 3f, 18f); close() }
                    canvas.drawPath(p, stroke); canvas.drawLine(3f, 10f, 21f, 10f, stroke) }
                2 -> { for (y in listOf(6f, 12f, 18f)) canvas.drawLine(4f, y, 20f, y, stroke)
                    stroke.style = Paint.Style.FILL; stroke.color = palette.surface
                    for ((x, y) in listOf(9f to 6f, 15f to 12f, 9f to 18f)) canvas.drawCircle(x, y, 2.5f, stroke)
                    stroke.style = Paint.Style.STROKE; stroke.color = tint
                    for ((x, y) in listOf(9f to 6f, 15f to 12f, 9f to 18f)) canvas.drawCircle(x, y, 2.5f, stroke) }
                3 -> { canvas.drawCircle(12f, 8f, 3.5f, stroke)
                    canvas.drawArc(4f, 14f, 20f, 27f, 190f, 160f, false, stroke) }
            }
            canvas.restore()
        }
        override fun setAlpha(alpha: Int) { stroke.alpha = alpha }
        override fun setColorFilter(filter: ColorFilter?) { stroke.colorFilter = filter }
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
