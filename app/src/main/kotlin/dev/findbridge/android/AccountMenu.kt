package dev.findbridge.android

import android.app.Activity
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import dev.findbridge.android.Ui.dp

/** Account actions use the same floating glass material and motion as display choices. */
internal class AccountMenu(
    private val activity: Activity,
    scene: GlassScene,
    paneBounds: () -> Rect,
) {
    private val popup = GlassPopup(activity, scene, paneBounds)

    fun show(anchor: View, connected: Boolean, connect: () -> Unit, signOut: () -> Unit) {
        val content =
            LinearLayout(activity).apply {
                id = R.id.account_actions
                orientation = LinearLayout.VERTICAL
                setPadding(activity.dp(6), activity.dp(6), activity.dp(6), activity.dp(6))
            }
        fun action(title: Int, destructive: Boolean = false, selected: () -> Unit) {
            content.addView(
                Button(activity).apply {
                    text = activity.getString(title)
                    isAllCaps = false
                    textSize = 16f
                    typeface = android.graphics.Typeface.DEFAULT
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    setPadding(activity.dp(16), activity.dp(10), activity.dp(16), activity.dp(10))
                    minHeight = activity.dp(48)
                    minimumHeight = activity.dp(48)
                    setTextColor(
                        if (destructive) if (Ui.dark) 0xffff453a.toInt() else 0xffb42318.toInt()
                        else Ui.ink
                    )
                    backgroundTintList = null
                    background = Ui.ripple(android.graphics.Color.TRANSPARENT, activity.dp(16))
                    isSoundEffectsEnabled = false
                    setOnClickListener {
                        dismiss()
                        selected()
                    }
                    NativeMotion.press(this, .985f)
                },
                LinearLayout.LayoutParams(-1, -2),
            )
        }
        action(if (connected) R.string.reconnect_account else R.string.sign_in, selected = connect)
        if (connected) {
            content.addView(
                View(activity).apply { setBackgroundColor(Ui.separator) },
                LinearLayout.LayoutParams(-1, activity.dp(1)).apply {
                    marginStart = activity.dp(16)
                    marginEnd = activity.dp(16)
                },
            )
            action(R.string.sign_out, destructive = true, selected = signOut)
        }
        popup.show(anchor, content, activity.dp(260))
    }

    fun dismiss() = popup.dismiss()
}
