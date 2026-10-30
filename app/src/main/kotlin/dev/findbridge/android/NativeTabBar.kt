package dev.findbridge.android

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.dynamicanimation.animation.DynamicAnimation
import dev.findbridge.android.Ui.column
import dev.findbridge.android.Ui.dp
import dev.findbridge.android.Ui.label

@android.annotation.SuppressLint(
    "ViewConstructor"
) // Constructed in Kotlin with the owning map scene.
internal class NativeTabBar(
    private val activity: Activity,
    scene: GlassScene,
    onSelect: (Int) -> Unit,
) : FrameLayout(activity) {
    val selection =
        View(activity).apply {
            id = MainActivity.TAB_SELECTION_ID
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    private val buttons = LinearLayout(activity)
    private var selected = 1
    private var vertical = false

    fun setVertical(value: Boolean) {
        if (vertical == value) return
        vertical = value
        NativeMotion.cancelTree(selection)
        buttons.orientation = if (value) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        for (i in 0 until buttons.childCount) buttons.getChildAt(i).layoutParams =
            LinearLayout.LayoutParams(if (value) -1 else 0, if (value) 0 else -1, 1f)
        post { update(selected, false) }
    }

    init {
        background = GlassDrawable(scene, this, activity.dp(36).toFloat(), .24f)
        elevation = activity.dp(8).toFloat()
        clipToOutline = true
        selection.background = GlassDrawable(scene, selection, activity.dp(30).toFloat(), .10f)
        addView(
            selection,
            LayoutParams(1, activity.dp(60)).apply {
                topMargin = activity.dp(4)
                leftMargin = activity.dp(5)
            },
        )
        addView(
            buttons,
            LayoutParams(-1, -1).apply {
                setMargins(activity.dp(5), activity.dp(4), activity.dp(5), activity.dp(4))
            },
        )
        listOf(R.string.people, R.string.devices, R.string.items, R.string.me).forEachIndexed {
            index,
            titleRes ->
            val title = activity.getString(titleRes)
            val tab =
                activity.column().apply {
                    gravity = Gravity.CENTER
                    contentDescription = title
                    isFocusable = true
                    addView(
                        TabGlyph(activity, index),
                        LinearLayout.LayoutParams(activity.dp(28), activity.dp(31)),
                    )
                    addView(activity.label(title, 10, Ui.muted).apply { gravity = Gravity.CENTER })
                    setOnClickListener { onSelect(index) }
                    NativeMotion.press(this, .91f)
                }
            buttons.addView(tab, LinearLayout.LayoutParams(0, -1, 1f))
        }
        addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
            if (r - l != oldR - oldL || b - t != oldB - oldT) update(selected, false)
        }
        update(1, false)
    }

    fun update(index: Int, animated: Boolean = true) {
        val changed = index != selected
        selected = index
        val cell =
            ((if (vertical) height - activity.dp(8) else width - activity.dp(10)) / 4f)
                .coerceAtLeast(0f)
        if (cell > 0) {
            val nextWidth = if (vertical) width - activity.dp(10) else cell.toInt()
            val nextHeight = if (vertical) cell.toInt() else height - activity.dp(8)
            if (
                selection.layoutParams.width != nextWidth ||
                    selection.layoutParams.height != nextHeight
            )
                selection.layoutParams =
                    selection.layoutParams.apply {
                        width = nextWidth
                        height = nextHeight
                    }
            if (animated) {
                NativeMotion.spring(
                    selection,
                    DynamicAnimation.TRANSLATION_X,
                    if (vertical) 0f else cell * index,
                    570f,
                    .82f,
                )
                NativeMotion.spring(
                    selection,
                    DynamicAnimation.TRANSLATION_Y,
                    if (vertical) cell * index else 0f,
                    570f,
                    .82f,
                )
            } else {
                selection.translationX = if (vertical) 0f else cell * index
                selection.translationY = if (vertical) cell * index else 0f
            }
            if (changed && animated && !NativeMotion.reduced(context)) {
                selection.scaleX = 1.06f
                NativeMotion.spring(selection, DynamicAnimation.SCALE_X, 1f, 520f, .8f)
            }
        }
        for (i in 0 until buttons.childCount) {
            val button = buttons.getChildAt(i) as LinearLayout
            val color = if (i == index) Ui.green else Ui.muted
            button.isSelected = i == index
            (button.getChildAt(0) as TabGlyph).tint = color
            (button.getChildAt(1) as TextView).setTextColor(color)
        }
    }
}
