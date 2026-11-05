package com.stealth.rootexecutor

import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.view.doOnLayout

/** Profile header sits behind an opaque sheet, which covers it as the page scrolls. */
internal class AboutProfileLayout(
    context: Context,
    private val header: View,
    private val sheet: LinearLayout,
    surfaceColor: Int,
    initialScrollY: Int,
    onScroll: (Int) -> Unit
) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()
    private val spacer = View(context).apply { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    private val scroller = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        clipToPadding = false
        overScrollMode = OVER_SCROLL_NEVER
        // Round the visible lower edge even while the sheet extends below
        // the viewport. Its own background supplies the scrolling top corners.
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val radius = dp(28)
                outline.setRoundRect(0, -radius, view.width, view.height, radius.toFloat())
            }
        }
        clipToOutline = true
    }

    init {
        addView(header, LayoutParams(-1, -2))
        val stack = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        stack.addView(spacer, LinearLayout.LayoutParams(-1, 0))
        sheet.background = GradientDrawable().apply {
            setColor(surfaceColor)
            cornerRadius = dp(28).toFloat()
        }
        sheet.setPadding(dp(12), dp(8), dp(12), dp(120))
        stack.addView(sheet, LinearLayout.LayoutParams(-1, -2))
        scroller.addView(stack)
        addView(scroller, LayoutParams(-1, -1))
        scroller.setOnScrollChangeListener { _, _, y, _, _ ->
            onScroll(y)
            val fraction = (y.toFloat() / header.height.coerceAtLeast(1)).coerceIn(0f, 1f)
            header.translationY = -y * 0.22f
            header.alpha = 1f - fraction
            header.importantForAccessibility = if (fraction == 1f) IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                else IMPORTANT_FOR_ACCESSIBILITY_AUTO
        }
        scroller.doOnLayout { scroller.scrollTo(0, initialScrollY) }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val headerHeight = header.measuredHeight
        if (spacer.layoutParams.height != headerHeight || sheet.minimumHeight != measuredHeight) {
            spacer.layoutParams = spacer.layoutParams.apply { height = headerHeight }
            sheet.minimumHeight = measuredHeight
            scroller.measure(MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY))
        }
    }
}
