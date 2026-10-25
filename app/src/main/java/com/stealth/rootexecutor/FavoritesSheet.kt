package com.stealth.rootexecutor

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.os.Build
import android.widget.FrameLayout
import android.view.Gravity
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs

/** Same-window sheet: the original toolbar touch stream stays alive throughout the reveal. */
internal class FavoritesSheet(
    private val host: FrameLayout,
    private val panel: View,
    private val onDismissed: () -> Unit
) {
    private val density = host.resources.displayMetrics.density
    private var animator: ValueAnimator? = null
    private var laidOut = false
    private var pendingDistance = 0f
    private var dragging = false
    private var pendingRelease: Pair<Float, Boolean>? = null
    private var closing = false
    private var removed = false
    private val previousAccessibility = mutableMapOf<View, Int>()
    private val dim = View(host.context).apply { setBackgroundColor(android.graphics.Color.BLACK); alpha = 0f }
    val contentView = object : FrameLayout(host.context) {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean = if (closing) true else super.dispatchTouchEvent(event)
    }
    val isShowing: Boolean get() = !removed && contentView.parent != null
    var revealFraction = 0f
        private set
    private val travel: Float get() = (panel.height + 8 * density).coerceAtLeast(1f)

    fun show(startDragging: Boolean) {
        dragging = startDragging
        contentView.isFocusableInTouchMode = true
        contentView.addView(dim, FrameLayout.LayoutParams(-1, -1))
        dim.setOnClickListener { dismiss() }
        val width = minOf(host.width - (32 * density).toInt(), (480 * density).toInt()).coerceAtLeast(1)
        contentView.addView(panel, FrameLayout.LayoutParams(width, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = (8 * density).toInt()
        })
        panel.isClickable = true
        // Hide before the first layout, then position it using its measured height.
        panel.translationY = host.height.toFloat()
        contentView.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        for (index in 0 until host.childCount) {
            val child = host.getChildAt(index)
            previousAccessibility[child] = child.importantForAccessibility
            child.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        panel.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val first = !laidOut
            laidOut = true
            if (dragging) dragTo(pendingDistance) else setFraction(revealFraction)
            val queuedRelease = pendingRelease
            pendingRelease = null
            if (queuedRelease != null) release(queuedRelease.first, queuedRelease.second)
            else if (first && !dragging && !closing) settle(true)
        }
        host.addView(contentView, FrameLayout.LayoutParams(-1, -1))
        contentView.requestApplyInsets()
        contentView.requestFocus()
    }

    fun dragTo(distance: Float) {
        if (removed || closing) return
        stopAnimator()
        dragging = true
        pendingDistance = distance.coerceAtLeast(0f)
        if (laidOut) setFraction(pendingDistance / travel)
    }

    fun release(upwardVelocity: Float, cancelled: Boolean = false) {
        if (removed || closing) return
        if (!laidOut) {
            pendingRelease = upwardVelocity to cancelled
            return
        }
        dragging = false
        val threshold = 650 * density
        val open = !cancelled && when {
            upwardVelocity > threshold -> true
            upwardVelocity < -threshold -> false
            else -> revealFraction >= 0.35f
        }
        if (open) settle(true) else dismiss()
    }

    fun attachDragHandle(handle: View) {
        var tracker: VelocityTracker? = null
        var startY = 0f
        var startDistance = 0f
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    stopAnimator()
                    startY = event.rawY
                    startDistance = revealFraction * travel
                    tracker?.recycle()
                    tracker = VelocityTracker.obtain().also { it.addMovement(event) }
                }
                MotionEvent.ACTION_MOVE -> {
                    tracker?.addMovement(event)
                    dragTo(startDistance + startY - event.rawY)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    tracker?.addMovement(event)
                    tracker?.computeCurrentVelocity(1000)
                    release(-(tracker?.yVelocity ?: 0f), event.actionMasked == MotionEvent.ACTION_CANCEL)
                    tracker?.recycle(); tracker = null
                }
            }
            true
        }
    }

    fun dismiss() {
        if (removed || closing) return
        closing = true
        dragging = false
        if (!laidOut) closeImmediately() else settle(false)
    }

    private fun setFraction(value: Float) {
        revealFraction = value.coerceIn(0f, 1f)
        panel.translationY = travel * (1f - revealFraction)
        dim.alpha = 0.6f * revealFraction
    }

    private fun settle(open: Boolean) {
        stopAnimator()
        val target = if (open) 1f else 0f
        if (!ValueAnimator.areAnimatorsEnabled()) {
            setFraction(target)
            if (!open) closeImmediately()
            return
        }
        val baseDuration = if (open) 280L else 220L
        val animation = ValueAnimator.ofFloat(revealFraction, target).apply {
            duration = (baseDuration * abs(target - revealFraction)).toLong().coerceIn(80L, baseDuration)
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { setFraction(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (animator !== animation) return
                    animator = null
                    if (!open) closeImmediately()
                }
            })
        }
        animator = animation
        animation.start()
    }

    private fun stopAnimator() {
        val running = animator
        animator = null
        running?.cancel()
    }

    fun closeImmediately() {
        if (removed) return
        removed = true
        stopAnimator()
        (contentView.parent as? ViewGroup)?.removeView(contentView)
        previousAccessibility.forEach { (view, importance) -> view.importantForAccessibility = importance }
        previousAccessibility.clear()
        onDismissed()
    }
}
