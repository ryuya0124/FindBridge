package dev.findbridge.android

import android.app.Activity
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import androidx.dynamicanimation.animation.DynamicAnimation
import dev.findbridge.android.Ui.dp

/** A choice menu anchored to its setting; dismissing it leaves the settings panel in place. */
internal class DisplayChoices(
    private val activity: Activity,
    private val scene: GlassScene,
    private val paneBounds: () -> Rect,
) {
    private var popup: PopupWindow? = null

    fun show(anchor: View, values: List<Pair<String, Boolean>>, selected: (Int) -> Unit) {
        dismiss()
        val rows =
            LinearLayout(activity).apply {
                id = R.id.display_choices
                orientation = LinearLayout.VERTICAL
                setPadding(activity.dp(8), activity.dp(8), activity.dp(8), activity.dp(8))
            }
        values.forEachIndexed { index, (title, checked) ->
            rows.addView(
                SettingsRow(activity, title, checked = checked) {
                    dismiss()
                    selected(index)
                },
                LinearLayout.LayoutParams(-1, -2),
            )
        }
        val scroll =
            ScrollView(activity).apply {
                isVerticalScrollBarEnabled = false
                addView(rows)
            }
        val card =
            FrameLayout(activity).apply {
                background = GlassDrawable(scene, this, activity.dp(22).toFloat(), .83f)
                clipToOutline = true
                addView(scroll, FrameLayout.LayoutParams(-1, -2))
            }
        val visible = Rect().also(anchor::getWindowVisibleDisplayFrame)
        visible.intersect(paneBounds())
        val position = IntArray(2).also(anchor::getLocationOnScreen)
        val below = visible.bottom - position[1] - anchor.height - activity.dp(4)
        val above = position[1] - visible.top - activity.dp(4)
        val width =
            minOf(anchor.width, visible.width() - activity.dp(24)).coerceAtLeast(activity.dp(120))
        card.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(
                (maxOf(above, below) - activity.dp(8)).coerceAtLeast(activity.dp(54)),
                View.MeasureSpec.AT_MOST,
            ),
        )
        popup =
            PopupWindow(card, width, card.measuredHeight, true).apply {
                setBackgroundDrawable(
                    Ui.rounded(android.graphics.Color.TRANSPARENT, activity.dp(22))
                )
                elevation = activity.dp(12).toFloat()
                isOutsideTouchable = true
                inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
                animationStyle = 0
                setOnDismissListener {
                    NativeMotion.cancelTree(card)
                    popup = null
                }
                // Keep the menu in its physical map pane, including tabletop folds.
                val offset =
                    if (below >= card.measuredHeight) activity.dp(4)
                    else -anchor.height - card.measuredHeight - activity.dp(4)
                showAsDropDown(anchor, 0, offset, Gravity.END)
            }
        if (!NativeMotion.reduced(activity)) {
            card.addOnLayoutChangeListener(
                object : View.OnLayoutChangeListener {
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
                        if (r <= l || b <= t) return
                        card.removeOnLayoutChangeListener(this)
                        val location = IntArray(2)
                        val anchorLocation = IntArray(2)
                        card.getLocationOnScreen(location)
                        anchor.getLocationOnScreen(anchorLocation)
                        card.pivotY =
                            if (location[1] < anchorLocation[1]) card.height.toFloat() else 0f
                        card.pivotX = card.width / 2f
                        card.alpha = 0f
                        card.scaleX = .96f
                        card.scaleY = .96f
                        card.animate().alpha(1f).setDuration(90).start()
                        NativeMotion.spring(card, DynamicAnimation.SCALE_X, 1f, 510f, .87f)
                        NativeMotion.spring(card, DynamicAnimation.SCALE_Y, 1f, 510f, .87f)
                    }
                }
            )
        }
    }

    fun dismiss() {
        popup?.dismiss()
        popup = null
    }
}
