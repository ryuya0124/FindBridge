package dev.findbridge.android

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce

/**
 * Shared, interruptible motion. Springs retain velocity when a gesture changes their destination.
 */
internal object NativeMotion {
    val ease = PathInterpolator(.2f, .8f, .2f, 1f)

    fun reduced(context: Context) =
        AppearanceSettings(context).reduceMotion || !ValueAnimator.areAnimatorsEnabled()

    fun duration(context: Context, millis: Long) = if (reduced(context)) 0L else millis

    private val contact =
        object : FloatPropertyCompat<View>("glassContact") {
            override fun getValue(view: View) = (view.background as? GlassDrawable)?.contact ?: 0f

            override fun setValue(view: View, value: Float) {
                (view.background as? GlassDrawable)?.contact = value.coerceIn(0f, 1f)
            }
        }

    @Suppress("UNCHECKED_CAST")
    private fun springs(view: View): MutableMap<FloatPropertyCompat<View>, SpringAnimation> {
        val existing =
            view.getTag(R.id.motion_springs)
                as? MutableMap<FloatPropertyCompat<View>, SpringAnimation>
        return existing
            ?: mutableMapOf<FloatPropertyCompat<View>, SpringAnimation>().also {
                view.setTag(R.id.motion_springs, it)
            }
    }

    fun spring(
        view: View,
        property: FloatPropertyCompat<View>,
        target: Float,
        stiffness: Float = 520f,
        damping: Float = .86f,
    ): SpringAnimation? {
        val animation =
            springs(view).getOrPut(property) {
                SpringAnimation(view, property).apply {
                    if (property === contact) setMinimumVisibleChange(.002f)
                    addUpdateListener { _, _, _ -> view.background?.invalidateSelf() }
                }
            }
        if (reduced(view.context)) {
            animation.cancel()
            property.setValue(view, target)
            view.background?.invalidateSelf()
            return null
        }
        animation.spring = SpringForce(target).setStiffness(stiffness).setDampingRatio(damping)
        animation.animateToFinalPosition(target)
        return animation
    }

    @SuppressLint("ClickableViewAccessibility")
    fun press(view: View, scale: Float = .94f) {
        if (view.getTag(R.id.motion_press) == true) return
        view.setTag(R.id.motion_press, true)
        view.isSoundEffectsEnabled = false
        view.setOnTouchListener { target, event ->
            if (!target.isEnabled) return@setOnTouchListener false
            val down = event.actionMasked == MotionEvent.ACTION_DOWN
            if (
                down ||
                    event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                spring(
                    target,
                    DynamicAnimation.SCALE_X,
                    if (down) scale else 1f,
                    if (down) 1100f else 650f,
                    .78f,
                )
                spring(
                    target,
                    DynamicAnimation.SCALE_Y,
                    if (down) scale else 1f,
                    if (down) 1100f else 650f,
                    .78f,
                )
                spring(target, contact, if (down) 1f else 0f, 850f, .9f)
            }
            false
        }
    }

    fun enter(view: View, offset: Float, delay: Long = 0) {
        if (reduced(view.context)) return
        view.translationY = offset
        view.alpha = 0f
        view.animate().alpha(1f).setStartDelay(delay).setDuration(180).setInterpolator(ease).start()
        view.postDelayed(
            {
                if (view.isAttachedToWindow)
                    spring(view, DynamicAnimation.TRANSLATION_Y, 0f, 450f, .88f)
            },
            delay,
        )
    }

    fun cancelTree(view: View) {
        if (view is RefreshButton) view.stopIndicator()
        view.animate().cancel()
        springs(view).values.forEach { it.cancel() }
        if (view is ViewGroup) for (i in 0 until view.childCount) cancelTree(view.getChildAt(i))
    }
}
