package dev.findbridge.android

import android.view.WindowManager
import android.widget.PopupWindow

/** Keep the front glass bright while dimming every window underneath it. */
internal object OverlayDimming {
    val amount: Float
        get() = if (Ui.dark) .32f else .24f

    fun apply(popup: PopupWindow) {
        // After showAsDropDown, this root belongs to the popup's own window.
        val decor = popup.contentView.rootView
        val params = decor.layoutParams as? WindowManager.LayoutParams ?: return
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_DIM_BEHIND
        params.dimAmount = amount
        decor.context.getSystemService(WindowManager::class.java).updateViewLayout(decor, params)
    }
}
