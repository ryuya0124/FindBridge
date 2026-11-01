package dev.findbridge.android

import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import dev.findbridge.android.MaterialControls.linearIndicator
import dev.findbridge.android.Ui.actionButton
import dev.findbridge.android.Ui.dp
import dev.findbridge.android.Ui.label

/** A native status row in the existing list, with no fabricated percentage or full-screen cover. */
@android.annotation.SuppressLint("ViewConstructor") // Built in Kotlin with its state and callbacks.
internal class InlineSignInView(
    activity: Activity,
    state: AppleSignInState,
    canceled: () -> Unit,
    retry: () -> Unit,
    showOfficial: () -> Unit,
) : LinearLayout(activity) {
    init {
        id = STATUS_ID
        orientation = VERTICAL
        setPadding(activity.dp(22), activity.dp(14), activity.dp(22), activity.dp(16))
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        val working = state == AppleSignInState.Working
        val heading = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        if (working) {
            heading.addView(
                RefreshButton(activity).apply {
                    id = SPINNER_ID
                    background = null
                    minimumWidth = 0
                    minimumHeight = 0
                    isClickable = false
                    isFocusable = false
                    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                    refreshing = true
                },
                LayoutParams(activity.dp(28), activity.dp(28)).apply { marginEnd = activity.dp(8) },
            )
        }
        val title =
            when (state) {
                AppleSignInState.Working -> R.string.signing_in
                AppleSignInState.Verification -> R.string.verification_help
                is AppleSignInState.Failed -> state.title
            }
        heading.addView(
            activity.label(activity.getString(title), 17, Ui.ink, true),
            LayoutParams(0, -2, 1f),
        )
        if (working) {
            heading.addView(
                activity.label(activity.getString(R.string.cancel), 14, Ui.blue).apply {
                    id = CANCEL_ID
                    gravity = Gravity.CENTER
                    minimumHeight = activity.dp(48)
                    minimumWidth = activity.dp(60)
                    background = Ui.ripple(Color.TRANSPARENT, activity.dp(12))
                    isFocusable = true
                    setOnClickListener { canceled() }
                }
            )
        }
        addView(heading, LayoutParams(-1, -2))
        if (working) {
            addView(
                activity.linearIndicator().apply {
                    id = PROGRESS_ID
                    isIndeterminate = true
                    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LayoutParams(-1, activity.dp(4)).apply { topMargin = activity.dp(12) },
            )
        } else {
            if (state is AppleSignInState.Failed) {
                addView(
                    activity.label(activity.getString(state.detail), 13, Ui.muted).apply {
                        setPadding(0, activity.dp(6), 0, activity.dp(12))
                    }
                )
                addView(
                    activity.actionButton(activity.getString(R.string.connect_again), false).apply {
                        id = RETRY_ID
                        setOnClickListener { retry() }
                    },
                    LayoutParams(-1, activity.dp(48)),
                )
            }
            addView(
                activity
                    .actionButton(activity.getString(R.string.apple_show_sign_in), false)
                    .apply {
                        id = OFFICIAL_ID
                        setOnClickListener { showOfficial() }
                    },
                LayoutParams(-1, activity.dp(48)),
            )
            addView(
                activity.actionButton(activity.getString(R.string.cancel), false).apply {
                    id = CANCEL_ID
                    setOnClickListener { canceled() }
                },
                LayoutParams(-1, activity.dp(48)),
            )
        }
    }

    companion object {
        const val STATUS_ID = 0x16fb0160
        const val SPINNER_ID = 0x16fb0161
        const val PROGRESS_ID = 0x16fb0162
        const val CANCEL_ID = 0x16fb0163
        const val RETRY_ID = 0x16fb0164
        const val OFFICIAL_ID = 0x16fb0165
    }
}
