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

/** Small local illustrations distinguish map styles without fetching extra map tiles. */
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
        var chosen = false
            set(value) {
                field = value
                invalidate()
            }

        override fun onDraw(canvas: Canvas) {
            canvas.save()
            canvas.scale(width / 100f, height / 64f)
            val aerial = mode != MapMode.STANDARD
            paint.color =
                if (aerial) 0xff647964.toInt()
                else if (Ui.dark) 0xff364039.toInt() else 0xffdce5d7.toInt()
            canvas.drawRoundRect(0f, 0f, 100f, 64f, 12f, 12f, paint)
            path.reset()
            path.addRoundRect(0f, 0f, 100f, 64f, 12f, 12f, Path.Direction.CW)
            canvas.clipPath(path)
            for (row in 0..2) for (column in 0..3) {
                paint.color =
                    if (aerial) {
                        if ((row + column) % 2 == 0) 0xffa6aa9b.toInt() else 0xff7e8776.toInt()
                    } else if (Ui.dark) 0xff4c514b.toInt() else 0xfff0e9dd.toInt()
                canvas.drawRect(
                    column * 27f + 3,
                    row * 23f + 3,
                    column * 27f + 22,
                    row * 23f + 19,
                    paint,
                )
            }
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 11f
            paint.color =
                if (aerial) 0xff497a8c.toInt()
                else if (Ui.dark) 0xff355d70.toInt() else 0xff9dc9e0.toInt()
            path.reset()
            path.moveTo(75f, -8f)
            path.cubicTo(78f, 16f, 39f, 43f, 49f, 74f)
            canvas.drawPath(path, paint)
            paint.strokeWidth = if (aerial) 2f else 4f
            paint.color =
                if (aerial) 0xffd2d0bc.toInt() else if (Ui.dark) 0xff818580.toInt() else Color.WHITE
            canvas.drawLine(0f, 21f, 100f, 43f, paint)
            canvas.drawLine(29f, 0f, 17f, 64f, paint)
            if (mode == MapMode.HYBRID) {
                paint.strokeWidth = 2.5f
                paint.color = Color.WHITE
                canvas.drawLine(9f, 13f, 25f, 13f, paint)
                canvas.drawLine(67f, 48f, 86f, 48f, paint)
            }
            paint.style = Paint.Style.FILL
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
