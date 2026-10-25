package com.stealth.rootexecutor

import android.content.Context
import android.os.Build
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.ScrollView
import kotlin.math.abs
import kotlin.math.max

/** Keep native fling/stretch physics while separating vertical scroll from row swipe selection. */
internal class BrowserScrollView(context: Context, accent: Int, private val onInteraction: () -> Unit) : ScrollView(context) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var startX = 0f
    private var startY = 0f
    private var horizontal = false
    private var directionLocked = false

    init {
        val density = resources.displayMetrics.density
        isFillViewport = true
        isSmoothScrollingEnabled = true
        overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
        isHorizontalScrollBarEnabled = false
        isVerticalScrollBarEnabled = true
        isScrollbarFadingEnabled = true
        scrollBarFadeDuration = 180
        scrollBarDefaultDelayBeforeFade = 600
        scrollBarSize = (2 * density).toInt().coerceAtLeast(1)
        isVerticalFadingEdgeEnabled = true
        setFadingEdgeLength((12 * density).toInt())
        if (Build.VERSION.SDK_INT >= 29) {
            setEdgeEffectColor(accent)
            verticalScrollbarThumbDrawable = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.argb(135, android.graphics.Color.red(accent), android.graphics.Color.green(accent), android.graphics.Color.blue(accent)))
                cornerRadius = 2 * density
            }
        }
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // A new touch takes priority over the short directory entrance transition.
                resetDirectoryTransition()
                onInteraction()
                startX = event.x; startY = event.y
                horizontal = false; directionLocked = false
                // The platform must be able to catch and stop an ongoing fling on touch-down.
                val intercepted = super.onInterceptTouchEvent(event)
                directionLocked = intercepted
                return intercepted
            }
            MotionEvent.ACTION_MOVE -> {
                if (!directionLocked) {
                    val dx = abs(event.x - startX)
                    val dy = abs(event.y - startY)
                    if (max(dx, dy) > slop) {
                        horizontal = dx > dy * 1.5f
                        directionLocked = true
                    }
                }
                if (horizontal) return false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                horizontal = false
                directionLocked = true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                horizontal = false
                directionLocked = false
            }
        }
        return super.onInterceptTouchEvent(event)
    }

    override fun shouldDelayChildPressedState() = false

    private fun resetDirectoryTransition() {
        getChildAt(0)?.apply {
            animate().cancel()
            alpha = 1f
            translationX = 0f
        }
    }

    override fun onDetachedFromWindow() {
        resetDirectoryTransition()
        super.onDetachedFromWindow()
    }
}
