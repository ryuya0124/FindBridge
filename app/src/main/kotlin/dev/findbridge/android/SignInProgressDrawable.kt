package dev.findbridge.android

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.view.animation.LinearInterpolator

/**
 * A visible rounded track; its moving segment indicates activity, never a completion percentage.
 */
internal class SignInProgressDrawable(private val context: Context) : Drawable(), Animatable {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase = .5f
    private var opacity = 255
    private var animator: ValueAnimator? = null

    override fun draw(canvas: Canvas) {
        val left = bounds.left.toFloat()
        val top = bounds.top.toFloat()
        val right = bounds.right.toFloat()
        val bottom = bounds.bottom.toFloat()
        val radius = bounds.height() / 2f
        fun color(value: Int) {
            paint.color = value
            paint.alpha = Color.alpha(value) * opacity / 255
        }
        color(Ui.separator)
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, paint)
        val width = bounds.width().toFloat()
        val segment = width * .32f
        val start = left - segment + (width + segment) * phase
        val saved = canvas.save()
        canvas.clipRect(bounds)
        color(Ui.green)
        canvas.drawRoundRect(start, top, start + segment, bottom, radius, radius, paint)
        canvas.restoreToCount(saved)
    }

    override fun start() {
        if (isRunning || NativeMotion.reduced(context)) return
        animator =
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1350
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    phase = it.animatedValue as Float
                    invalidateSelf()
                }
                start()
            }
    }

    override fun stop() {
        animator?.cancel()
        animator = null
    }

    override fun isRunning() = animator?.isRunning == true

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        if (!visible) stop()
        return super.setVisible(visible, restart)
    }

    override fun setAlpha(alpha: Int) {
        opacity = alpha
        invalidateSelf()
    }

    override fun setColorFilter(filter: ColorFilter?) {
        paint.colorFilter = filter
        invalidateSelf()
    }

    @Suppress("DEPRECATION") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
