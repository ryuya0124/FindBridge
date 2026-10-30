package dev.findbridge.android

import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlin.math.abs

/** The sheet takes over from its list only when that list reaches its upper edge. */
internal class SheetGestureLayout(context: Context) : LinearLayout(context) {
    var dragEnabled = true
    var canExpand: () -> Boolean = { true }
    var onDragStart: () -> Unit = {}
    var onDragBy: (Float) -> Unit = {}
    var onDragEnd: (Float) -> Unit = {}
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var velocity: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var dragging = false
    private var scroll: ScrollView? = null

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            velocity?.recycle()
            velocity = VelocityTracker.obtain()
            downX = event.rawX
            downY = event.rawY
            lastY = event.rawY
            dragging = false
            scroll = findScroll(this, event.rawX, event.rawY)
        }
        val screenEvent = MotionEvent.obtain(event)
        screenEvent.offsetLocation(event.rawX - event.x, event.rawY - event.y)
        velocity?.addMovement(screenEvent)
        screenEvent.recycle()
        val handled = super.dispatchTouchEvent(event)
        if (
            event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            velocity?.recycle()
            velocity = null
            dragging = false
        }
        return handled
    }

    private fun startIfNeeded(event: MotionEvent): Boolean {
        if (!dragEnabled) return false
        if (dragging) return true
        val dy = event.rawY - downY
        if (abs(dy) <= slop || abs(dy) <= abs(event.rawX - downX)) return false
        if ((dy > 0 && scroll?.canScrollVertically(-1) != true) || (dy < 0 && canExpand())) {
            dragging = true
            onDragStart()
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        return dragging
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean =
        event.actionMasked == MotionEvent.ACTION_MOVE && startIfNeeded(event)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_MOVE ->
                if (startIfNeeded(event)) {
                    onDragBy(lastY - event.rawY)
                    lastY = event.rawY
                }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL ->
                if (dragging) {
                    velocity?.computeCurrentVelocity(1000)
                    onDragEnd(
                        if (event.actionMasked == MotionEvent.ACTION_CANCEL) 0f
                        else -(velocity?.yVelocity ?: 0f)
                    )
                } else if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun findScroll(view: View, x: Float, y: Float): ScrollView? {
        val origin = IntArray(2)
        view.getLocationOnScreen(origin)
        if (
            x < origin[0] ||
                y < origin[1] ||
                x > origin[0] + view.width ||
                y > origin[1] + view.height
        )
            return null
        if (view is ScrollView) return view
        if (view is ViewGroup)
            for (i in 0 until view.childCount) findScroll(view.getChildAt(i), x, y)?.let {
                return it
            }
        return null
    }
}
