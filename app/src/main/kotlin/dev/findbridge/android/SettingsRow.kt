package dev.findbridge.android

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import dev.findbridge.android.Ui.dp
import dev.findbridge.android.Ui.label

/** One accessible settings action, with a value or a checked choice at the trailing edge. */
@android.annotation.SuppressLint("ViewConstructor")
internal class SettingsRow(
    activity: Activity,
    title: String,
    value: String? = null,
    private val checked: Boolean? = null,
    onClick: (View) -> Unit,
) : LinearLayout(activity) {
    init {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = activity.dp(54)
        setPadding(activity.dp(15), activity.dp(9), activity.dp(12), activity.dp(9))
        background = Ui.ripple(Ui.surface, activity.dp(15))
        isFocusable = true
        contentDescription = listOfNotNull(title, value).joinToString(", ")
        isSelected = checked == true
        addView(activity.label(title, 16).apply { maxLines = 2 }, LayoutParams(0, -2, 1f))
        if (value != null) {
            addView(
                activity.label(value, 14, Ui.muted).apply {
                    gravity = Gravity.END
                    maxLines = 2
                    maxWidth = activity.dp(132)
                },
                LayoutParams(-2, -2).apply { marginStart = activity.dp(12) },
            )
        }
        if (checked == null || checked) {
            addView(
                SettingsAccessory(activity, checked == true),
                LayoutParams(activity.dp(18), activity.dp(22)).apply {
                    marginStart = activity.dp(9)
                },
            )
        }
        for (index in 0 until childCount) {
            getChildAt(index).importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        setOnClickListener { onClick(it) }
        NativeMotion.press(this, .985f)
    }

    override fun getAccessibilityClassName(): CharSequence =
        if (checked == null) Button::class.java.name else RadioButton::class.java.name

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.isCheckable = checked != null
        info.isChecked = checked == true
    }
}

/** Drawn independently of the user's font; respects the direction of the containing layout. */
@android.annotation.SuppressLint("ViewConstructor")
internal class SettingsAccessory(
    context: android.content.Context,
    private val check: Boolean = false,
) : View(context) {
    private val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

    override fun onDraw(canvas: Canvas) {
        val unit = resources.displayMetrics.density
        canvas.save()
        canvas.translate(width / 2f, height / 2f)
        canvas.scale(
            unit * if (!check && layoutDirection == LAYOUT_DIRECTION_RTL) -1f else unit,
            unit,
        )
        paint.color = if (check) Ui.positiveText else Ui.muted
        paint.strokeWidth = if (check) 2.2f else 1.7f
        if (check) {
            canvas.drawLine(-5f, 0f, -1.5f, 3.5f, paint)
            canvas.drawLine(-1.5f, 3.5f, 5.5f, -4f, paint)
        } else {
            canvas.drawLine(-2f, -4f, 2f, 0f, paint)
            canvas.drawLine(2f, 0f, -2f, 4f, paint)
        }
        canvas.restore()
    }
}
