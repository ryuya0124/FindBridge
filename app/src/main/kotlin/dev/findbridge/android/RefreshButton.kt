package dev.findbridge.android

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.Button

/** The activity indicator animates inside a fixed button, without rotating its glass surface. */
@android.annotation.SuppressLint(
    "AppCompatCustomView"
) // Platform-themed button; M2 is scoped to switches.
internal class RefreshButton(context: Context) : Button(context) {
    enum class Symbol {
        REFRESH,
        MORE,
        ADD,
        CLOSE,
    }

    var symbol: Symbol? = null
    private val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeCap = Paint.Cap.ROUND
            strokeWidth = resources.displayMetrics.density * 1.8f
        }
    private var animator: ValueAnimator? = null
    private var phase = 0
    var refreshing = false
        set(value) {
            if (field == value) return
            field = value
            if (android.os.Build.VERSION.SDK_INT >= 30)
                stateDescription = if (value) context.getString(R.string.loading) else null
            updateMotion()
            invalidate()
        }

    private fun updateMotion() {
        val running =
            refreshing &&
                isAttachedToWindow &&
                isShown &&
                windowVisibility == View.VISIBLE &&
                !NativeMotion.reduced(context)
        if (running) {
            if (animator?.isStarted == true) return
            animator =
                ValueAnimator.ofFloat(0f, 12f).apply {
                    duration = 850
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    addUpdateListener {
                        val next = (it.animatedValue as Float).toInt() % 12
                        if (phase != next) {
                            phase = next
                            invalidate()
                        }
                    }
                    start()
                }
        } else {
            animator?.cancel()
            animator = null
            phase = 0
        }
    }

    fun stopIndicator() {
        animator?.cancel()
        animator = null
        phase = 0
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateMotion()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        phase = 0
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateMotion()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        updateMotion()
    }

    override fun onDraw(canvas: Canvas) {
        if (!refreshing) {
            if (symbol == null) super.onDraw(canvas)
            else {
                val unit = resources.displayMetrics.density
                val x = width / 2f
                val y = height / 2f
                paint.color = currentTextColor
                paint.alpha = if (isEnabled) 255 else 110
                paint.style = Paint.Style.STROKE
                when (symbol) {
                    Symbol.REFRESH -> {
                        canvas.drawArc(
                            x - 8 * unit,
                            y - 8 * unit,
                            x + 8 * unit,
                            y + 8 * unit,
                            15f,
                            300f,
                            false,
                            paint,
                        )
                        canvas.drawLine(
                            x + 6 * unit,
                            y - 6 * unit,
                            x + 6 * unit,
                            y - 11 * unit,
                            paint,
                        )
                        canvas.drawLine(
                            x + 6 * unit,
                            y - 6 * unit,
                            x + 1 * unit,
                            y - 6 * unit,
                            paint,
                        )
                    }
                    Symbol.MORE -> {
                        paint.style = Paint.Style.FILL
                        for (offset in listOf(-6, 0, 6)) canvas.drawCircle(
                            x + offset * unit,
                            y,
                            1.5f * unit,
                            paint,
                        )
                    }
                    Symbol.ADD -> {
                        canvas.drawLine(x - 7 * unit, y, x + 7 * unit, y, paint)
                        canvas.drawLine(x, y - 7 * unit, x, y + 7 * unit, paint)
                    }
                    Symbol.CLOSE -> {
                        canvas.drawLine(
                            x - 5 * unit,
                            y - 5 * unit,
                            x + 5 * unit,
                            y + 5 * unit,
                            paint,
                        )
                        canvas.drawLine(
                            x + 5 * unit,
                            y - 5 * unit,
                            x - 5 * unit,
                            y + 5 * unit,
                            paint,
                        )
                    }
                    null -> Unit
                }
                paint.style = Paint.Style.STROKE
            }
            return
        }
        paint.style = Paint.Style.STROKE
        val unit = resources.displayMetrics.density
        val centerX = width / 2f
        val centerY = height / 2f
        for (index in 0 until 12) {
            val angle = (index * 30.0 - 90) * Math.PI / 180
            paint.color = Ui.muted
            paint.alpha = 45 + ((index - phase + 12) % 12) * 19
            canvas.drawLine(
                centerX + kotlin.math.cos(angle).toFloat() * 6 * unit,
                centerY + kotlin.math.sin(angle).toFloat() * 6 * unit,
                centerX + kotlin.math.cos(angle).toFloat() * 10 * unit,
                centerY + kotlin.math.sin(angle).toFloat() * 10 * unit,
                paint,
            )
        }
    }
}
