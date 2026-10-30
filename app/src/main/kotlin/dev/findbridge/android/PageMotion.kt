package dev.findbridge.android

import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.dynamicanimation.animation.DynamicAnimation

internal enum class PageChange {
    NONE,
    TAB_LEFT,
    TAB_RIGHT,
    DETAIL,
    LIST,
}

/** Keeps the outgoing hierarchy in a drawing-only overlay, so it cannot receive a second tap. */
internal class PageMotion(private val host: FrameLayout) {
    private var outgoing: View? = null
    private var generation = 0
    var running = false
        private set

    var onSettled: () -> Unit = {}

    fun replace(previous: LinearLayout?, next: LinearLayout, change: PageChange) {
        finish()
        previous?.let { host.removeView(it) }
        host.addView(next, FrameLayout.LayoutParams(-1, -1))
        if (previous == null || change == PageChange.NONE || NativeMotion.reduced(host.context)) {
            previous?.let(NativeMotion::cancelTree)
            return
        }
        running = true
        outgoing = previous
        host.overlay.add(previous)
        NativeMotion.cancelTree(previous)
        val requestGeneration = generation
        previous.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        val density = host.resources.displayMetrics.density
        val horizontal = change == PageChange.TAB_LEFT || change == PageChange.TAB_RIGHT
        val direction = if (change == PageChange.TAB_LEFT || change == PageChange.LIST) -1f else 1f
        val distance = (if (horizontal) 30f else 24f) * density * direction
        if (horizontal) next.translationX = distance else next.translationY = distance
        next.alpha = 0f
        next
            .animate()
            .alpha(1f)
            .setStartDelay(20)
            .setDuration(170)
            .setInterpolator(NativeMotion.ease)
            .start()
        NativeMotion.spring(
            next,
            if (horizontal) DynamicAnimation.TRANSLATION_X else DynamicAnimation.TRANSLATION_Y,
            0f,
            540f,
            .92f,
        )
        previous
            .animate()
            .alpha(0f)
            .setDuration(140)
            .setInterpolator(NativeMotion.ease)
            .withEndAction {
                if (outgoing === previous) {
                    host.overlay.remove(previous)
                    NativeMotion.cancelTree(previous)
                    outgoing = null
                }
            }
            .start()
        host.postDelayed(
            {
                if (running && generation == requestGeneration) {
                    running = false
                    onSettled()
                }
            },
            440,
        )
    }

    fun finish() {
        generation++
        outgoing?.let {
            NativeMotion.cancelTree(it)
            host.overlay.remove(it)
        }
        outgoing = null
        running = false
    }
}
