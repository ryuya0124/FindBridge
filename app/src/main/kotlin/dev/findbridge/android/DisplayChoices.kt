package dev.findbridge.android

import android.app.Activity
import android.graphics.Rect
import android.view.View
import android.widget.LinearLayout
import dev.findbridge.android.Ui.dp

/** A choice menu anchored to its setting; dismissal leaves the settings panel in place. */
internal class DisplayChoices(
    private val activity: Activity,
    scene: GlassScene,
    paneBounds: () -> Rect,
    dismissed: () -> Unit = {},
) {
    private val popup = GlassPopup(activity, scene, paneBounds, dismissed)
    val isShowing
        get() = popup.isShowing

    fun show(anchor: View, values: List<Pair<String, Boolean>>, selected: (Int) -> Unit) {
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
                    }
                    .apply {
                        // The menu has one glass surface; rows only add their pressed ripple.
                        background = Ui.ripple(android.graphics.Color.TRANSPARENT, activity.dp(15))
                    },
                LinearLayout.LayoutParams(-1, -2),
            )
        }
        popup.show(anchor, rows, anchor.width)
    }

    fun dismiss() = popup.dismiss()
}
