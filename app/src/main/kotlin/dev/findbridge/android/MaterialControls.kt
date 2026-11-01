package dev.findbridge.android

import android.app.Activity
import android.view.View
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.BaseProgressIndicator
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.LabelFormatter
import com.google.android.material.slider.Slider
import dev.findbridge.android.Ui.dp

/** Standard controls use Material 3 Expressive; custom Apple surfaces live in Ui and GlassPopup. */
internal object MaterialControls {
    fun Activity.materialSwitch() =
        MaterialSwitch(this).apply {
            setTextColor(Ui.ink)
            textSize = 14f
            minHeight = dp(48)
            switchPadding = dp(16)
            isSaveEnabled = false
        }

    fun Activity.materialSlider() =
        Slider(this).apply {
            valueFrom = 0f
            valueTo = 100f
            stepSize = 1f
            labelBehavior = LabelFormatter.LABEL_GONE
            isSaveEnabled = false
        }

    fun Activity.linearIndicator(indeterminate: Boolean = true) =
        LinearProgressIndicator(this).apply {
            isIndeterminate = indeterminate
            setIndicatorColor(Ui.green)
            trackColor = Ui.separator
            trackThickness = dp(4)
            trackCornerRadius = dp(2)
            isSaveEnabled = false
            respectMotion(this@linearIndicator)
        }

    fun Activity.circularIndicator() =
        CircularProgressIndicator(this).apply {
            isIndeterminate = true
            setIndicatorColor(Ui.green)
            trackColor = Ui.separator
            indicatorSize = dp(28)
            indicatorInset = 0
            trackThickness = dp(3)
            isSaveEnabled = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            respectMotion(this@circularIndicator)
        }

    private fun BaseProgressIndicator<*>.respectMotion(activity: Activity) {
        // Material owns its loading animation and respects Android's animator scale.
        // The app preference also disables the indicator's entrance/exit transitions.
        if (NativeMotion.reduced(activity)) {
            setShowAnimationBehavior(BaseProgressIndicator.SHOW_NONE)
            setHideAnimationBehavior(BaseProgressIndicator.HIDE_NONE)
        }
    }
}
