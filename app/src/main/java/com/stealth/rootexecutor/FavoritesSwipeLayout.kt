package com.stealth.rootexecutor

import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.LinearLayout
import kotlin.math.abs

/** Intercept upward drags across both bottom rows, leaving normal button taps intact. */
internal class FavoritesSwipeLayout(
    context: Context,
    private val canStart: (MotionEvent) -> Boolean,
    private val openSheet: () -> FavoritesSheet
) : LinearLayout(context) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var startY = 0f
    private var startX = 0f
    private var candidate = false
    private var sheet: FavoritesSheet? = null
    private var tracker: VelocityTracker? = null

    private fun begin(event: MotionEvent): Boolean {
        if (!candidate || sheet != null) return sheet != null
        val distance = startY - event.rawY
        if (distance > slop && distance > abs(event.rawX - startX)) {
            sheet = openSheet().also { it.dragTo(distance) }
            return true
        }
        return false
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                clearTracker()
                candidate = canStart(event)
                startY = event.rawY; startX = event.rawX
                if (candidate) tracker = VelocityTracker.obtain().also { it.addMovement(event) }
            }
            MotionEvent.ACTION_MOVE -> if (begin(event)) return true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { candidate = false; clearTracker() }
            MotionEvent.ACTION_POINTER_DOWN -> { candidate = false; clearTracker() }
        }
        return super.onInterceptTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_MOVE) begin(event)
        val active = sheet
        if (active != null) {
            tracker?.addMovement(event)
            active.dragTo(startY - event.rawY)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                tracker?.computeCurrentVelocity(1000)
                active.release(-(tracker?.yVelocity ?: 0f), event.actionMasked != MotionEvent.ACTION_UP)
                sheet = null; candidate = false; clearTracker()
            }
            return true
        }
        if (candidate) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                candidate = false; clearTracker()
            }
            return true
        }
        return super.onTouchEvent(event)
    }

    private fun clearTracker() { tracker?.recycle(); tracker = null }

    override fun onDetachedFromWindow() {
        sheet?.release(0f, cancelled = true)
        sheet = null; candidate = false; clearTracker()
        super.onDetachedFromWindow()
    }
}
