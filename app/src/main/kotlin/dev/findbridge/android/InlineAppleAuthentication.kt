package dev.findbridge.android

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/** Keeps automatic authentication under the opaque native UI; only verification comes forward. */
internal class InlineAppleAuthentication(
    private val activity: Activity,
    private val nativeRoot: View,
    automatic: Boolean = true,
    private val changed: () -> Unit,
    finished: (Boolean) -> Unit,
) {
    var state: AppleSignInState = AppleSignInState.Working
        private set

    var officialVisible = false
        private set

    private var closed = false
    private val nativeAccessibility = nativeRoot.importantForAccessibility
    private val secureWindow = SecureWindowLease.acquire(activity.window)
    private val content = activity.findViewById<FrameLayout>(android.R.id.content)
    val flow =
        AppleAuthFlow(
            activity,
            automatic,
            changed = { next ->
                if (!closed) {
                    state = next
                    showOfficial(next == AppleSignInState.Verification)
                    changed()
                }
            },
            finished = { success -> if (!closed) finished(success) },
        )

    init {
        flow.view.interactive = false
        flow.view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        flow.view.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        content.addView(flow.view, 0, FrameLayout.LayoutParams(-1, -1))
    }

    fun start() {
        flow.onStart()
        flow.onResume()
        flow.start()
    }

    private fun showOfficial(show: Boolean) {
        officialVisible = show
        flow.view.interactive = show
        flow.view.importantForAccessibility =
            if (show) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        nativeRoot.importantForAccessibility =
            if (show) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else nativeAccessibility
        if (show) flow.view.bringToFront() else nativeRoot.bringToFront()
        flow.view.requestApplyInsets()
        nativeRoot.requestApplyInsets()
    }

    fun close(changingConfigurations: Boolean = false) {
        if (closed) return
        closed = true
        if (changingConfigurations && state == AppleSignInState.Working) flow.retainLogin()
        flow.dispose(pauseAutomatic = !changingConfigurations)
        (flow.view.parent as? ViewGroup)?.removeView(flow.view)
        nativeRoot.importantForAccessibility = nativeAccessibility
        secureWindow.close()
        nativeRoot.requestFocus()
        nativeRoot.requestApplyInsets()
    }
}
