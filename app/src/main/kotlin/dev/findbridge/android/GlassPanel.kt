package dev.findbridge.android

import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.dynamicanimation.animation.DynamicAnimation
import dev.findbridge.android.Ui.actionButton
import dev.findbridge.android.Ui.dp
import dev.findbridge.android.Ui.space

/**
 * Native anchored presentation. Its backdrop remains the same map during presentation and
 * dismissal.
 */
@android.annotation.SuppressLint(
    "ViewConstructor"
) // Constructed in Kotlin with the owning map scene.
internal class GlassPanel(
    private val activity: Activity,
    private val root: FrameLayout,
    scene: GlassScene,
    content: LinearLayout,
    anchor: View?,
    safeBounds: ScreenRect,
    private val onDismiss: () -> Unit,
) : FrameLayout(activity) {
    private val panel = FrameLayout(activity)
    private val scrim = View(activity)
    private var closing = false
    private val accessibility = mutableMapOf<View, Int>()

    init {
        id = MainActivity.PANEL_ID
        isClickable = true
        isFocusableInTouchMode = true
        for (i in 0 until root.childCount) root.getChildAt(i).let { view ->
            accessibility[view] = view.importantForAccessibility
            view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        scrim.setBackgroundColor(Color.BLACK)
        scrim.alpha = 0f
        addView(scrim, LayoutParams(-1, -1))
        setOnClickListener { dismiss() }
        panel.background = GlassDrawable(scene, panel, activity.dp(30).toFloat(), .83f)
        panel.clipToOutline = true
        panel.elevation = activity.dp(12).toFloat()
        panel.isClickable = true
        val scroll = ScrollView(activity).apply { isFillViewport = false }
        content.setPadding(
            content.paddingLeft,
            activity.dp(18),
            content.paddingRight,
            activity.dp(16),
        )
        activity.space(content, 14)
        content.addView(
            activity.actionButton(activity.getString(R.string.done), false).apply {
                setOnClickListener { dismiss() }
            },
            LinearLayout.LayoutParams(-1, activity.dp(46)),
        )
        scroll.addView(content)
        panel.addView(scroll, LayoutParams(-1, -2))
        val width =
            minOf(safeBounds.width - activity.dp(24), activity.dp(360))
                .coerceAtLeast(activity.dp(120))
        val rootOrigin = IntArray(2)
        root.getLocationOnScreen(rootOrigin)
        val anchorOrigin = IntArray(2)
        anchor?.getLocationOnScreen(anchorOrigin)
        val proposedTop =
            if (anchor != null) anchorOrigin[1] - rootOrigin[1] + anchor.height + activity.dp(12)
            else safeBounds.top + activity.dp(28)
        val top =
            proposedTop
                .coerceAtMost(safeBounds.bottom - activity.dp(180))
                .coerceAtLeast(safeBounds.top + activity.dp(8))
        val maximum = (safeBounds.bottom - top - activity.dp(16)).coerceAtLeast(activity.dp(60))
        addView(
            panel,
            LayoutParams(width, -2, Gravity.TOP or Gravity.END).apply {
                topMargin = top
                rightMargin = root.width - safeBounds.right + activity.dp(12)
            },
        )
        panel.addOnLayoutChangeListener(
            object : OnLayoutChangeListener {
                override fun onLayoutChange(
                    v: View,
                    l: Int,
                    t: Int,
                    r: Int,
                    b: Int,
                    ol: Int,
                    ot: Int,
                    or: Int,
                    ob: Int,
                ) {
                    if (b - t > maximum)
                        panel.layoutParams = panel.layoutParams.apply { height = maximum }
                    panel.removeOnLayoutChangeListener(this)
                    panel.pivotX =
                        if (anchor != null) anchorOrigin[0] - rootOrigin[0] + anchor.width / 2f - l
                        else panel.width / 2f
                    panel.pivotY =
                        if (anchor != null) anchorOrigin[1] - rootOrigin[1] + anchor.height / 2f - t
                        else 0f
                    reveal()
                }
            }
        )
        root.addView(this, LayoutParams(-1, -1))
        requestFocus()
        accessibilityPaneTitle = activity.getString(R.string.map_display_settings)
    }

    private fun reveal() {
        if (closing) return
        if (NativeMotion.reduced(context)) {
            scrim.alpha = .10f
            return
        }
        panel.alpha = 0f
        panel.scaleX = .86f
        panel.scaleY = .86f
        panel.animate().alpha(1f).setDuration(130).setInterpolator(NativeMotion.ease).start()
        scrim.animate().alpha(.10f).setDuration(180).start()
        NativeMotion.spring(panel, DynamicAnimation.SCALE_X, 1f, 510f, .87f)
        NativeMotion.spring(panel, DynamicAnimation.SCALE_Y, 1f, 510f, .87f)
    }

    fun dismiss(immediate: Boolean = false) {
        if (closing) return
        closing = true
        NativeMotion.cancelTree(panel)
        val remove = {
            root.removeView(this)
            accessibility.forEach { (view, value) -> view.importantForAccessibility = value }
            onDismiss()
        }
        if (immediate || NativeMotion.reduced(context)) {
            remove()
            return
        }
        scrim.animate().alpha(0f).setDuration(150).start()
        panel
            .animate()
            .alpha(0f)
            .scaleX(.9f)
            .scaleY(.9f)
            .setDuration(160)
            .setInterpolator(NativeMotion.ease)
            .withEndAction(remove)
            .start()
    }
}
