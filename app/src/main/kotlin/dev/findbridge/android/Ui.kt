package dev.findbridge.android

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

internal object Ui {
    const val background = 0xfff2f2f7.toInt()
    const val ink = 0xff1c1c1e.toInt()
    const val muted = 0xff8e8e93.toInt()
    const val green = 0xff28b14e.toInt()
    const val blue = 0xff007aff.toInt()
    const val surface = 0xfffbfbfd.toInt()

    fun Activity.dp(value: Float): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    fun Activity.dp(value: Int): Int = dp(value.toFloat())

    fun Activity.column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    fun Activity.label(value: String, size: Int, color: Int = ink, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color)
            if (bold) typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setLineSpacing(dp(2).toFloat(), 1f)
        }

    fun rounded(color: Int, radius: Int) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
        }

    fun ripple(color: Int, radius: Int) =
        RippleDrawable(
            ColorStateList.valueOf(0x15000000),
            rounded(color, radius),
            null,
        )

    fun Activity.actionButton(value: String, primary: Boolean = true) =
        Button(this).apply {
            text = value
            textSize = 15f
            isAllCaps = false
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (primary) Color.WHITE else blue)
            background = ripple(if (primary) green else Color.WHITE, dp(16))
            setPadding(dp(14), dp(8), dp(14), dp(8))
            minHeight = dp(48)
        }

    fun Activity.space(parent: LinearLayout, height: Int) {
        parent.addView(View(this), LinearLayout.LayoutParams(1, dp(height)))
    }
}
