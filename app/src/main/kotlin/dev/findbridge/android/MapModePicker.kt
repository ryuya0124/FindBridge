package dev.findbridge.android

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.RadioButton
import dev.findbridge.android.Ui.dp
import dev.findbridge.android.Ui.label

/** Real map samples distinguish map styles without rendering extra live maps in the menu. */
@android.annotation.SuppressLint("ViewConstructor")
internal class MapModePicker(activity: Activity, selected: MapMode, onSelect: (MapMode) -> Unit) :
    LinearLayout(activity) {
    private val choices = mutableListOf<Choice>()

    init {
        MapMode.entries.forEachIndexed { index, mode ->
            val choice = Choice(activity, mode)
            choices += choice
            choice.setOnClickListener {
                update(mode)
                onSelect(mode)
            }
            addView(
                choice,
                LayoutParams(0, -2, 1f).apply {
                    if (index > 0) marginStart = activity.dp(8)
                },
            )
        }
        update(selected)
    }

    private fun update(mode: MapMode) = choices.forEach { it.select(it.mode == mode) }

    private class Choice(private val activity: Activity, val mode: MapMode) :
        LinearLayout(activity) {
        private val title = activity.label(activity.getString(mode.titleRes), 12, Ui.ink, true)
        private val preview = MapPreview(activity, mode)

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            setPadding(activity.dp(3), activity.dp(3), activity.dp(3), activity.dp(7))
            contentDescription = activity.getString(mode.titleRes)
            isFocusable = true
            addView(preview, LayoutParams(-1, activity.dp(64)))
            title.gravity = Gravity.CENTER
            title.maxLines = 2
            title.minHeight = activity.dp(34)
            addView(title, LayoutParams(-1, -2))
            for (index in 0 until childCount) {
                getChildAt(index).importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            NativeMotion.press(this, .96f)
        }

        fun select(active: Boolean) {
            isSelected = active
            preview.chosen = active
            background =
                GradientDrawable().apply {
                    cornerRadius = activity.dp(17).toFloat()
                    setColor(Ui.surface)
                    setStroke(
                        activity.dp(if (active) 2 else 1),
                        if (active) Ui.green else Ui.separator,
                    )
                }
            title.setTextColor(if (active) Ui.positiveText else Ui.ink)
        }

        override fun getAccessibilityClassName(): CharSequence = RadioButton::class.java.name

        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.isCheckable = true
            info.isChecked = isSelected
        }
    }

    private class MapPreview(context: android.content.Context, private val mode: MapMode) :
        View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        private val image =
            requireNotNull(
                context.getDrawable(
                    when (mode) {
                        MapMode.STANDARD ->
                            if (Ui.dark) R.drawable.map_preview_standard_dark
                            else R.drawable.map_preview_standard
                        MapMode.AERIAL -> R.drawable.map_preview_aerial
                        MapMode.HYBRID -> R.drawable.map_preview_hybrid
                    }
                )
            )
        var chosen = false
            set(value) {
                field = value
                invalidate()
            }

        override fun onDraw(canvas: Canvas) {
            canvas.save()
            canvas.scale(width / 100f, height / 64f)
            path.reset()
            path.addRoundRect(0f, 0f, 100f, 64f, 12f, 12f, Path.Direction.CW)
            canvas.clipPath(path)
            image.setBounds(0, 0, 100, 64)
            image.draw(canvas)
            if (chosen) {
                paint.color = Ui.positiveText
                canvas.drawCircle(84f, 14f, 8f, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2f
                paint.strokeCap = Paint.Cap.ROUND
                paint.color = if (Ui.dark) 0xff102d17.toInt() else Color.WHITE
                canvas.drawLine(80f, 14f, 83f, 17f, paint)
                canvas.drawLine(83f, 17f, 88f, 11f, paint)
                paint.style = Paint.Style.FILL
            }
            canvas.restore()
        }
    }
}
