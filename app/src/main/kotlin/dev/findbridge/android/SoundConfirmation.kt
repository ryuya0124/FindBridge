package dev.findbridge.android

import android.app.Activity
import android.app.AlertDialog

/** The request callback runs only after the owner presses the explicit playback action. */
internal object SoundConfirmation {
    fun show(
        activity: Activity,
        name: String,
        stop: Boolean = false,
        google: Boolean = false,
        confirmed: () -> Unit,
    ): AlertDialog {
        var consumed = false
        return AlertDialog.Builder(activity)
            .setTitle(if (stop) R.string.stop_sound else R.string.play_sound)
            .setMessage(
                activity.getString(
                    if (stop) R.string.stop_sound_confirmation
                    else if (google) R.string.google_sound_confirmation
                    else R.string.sound_confirmation,
                    name,
                )
            )
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(if (stop) R.string.stop_sound else R.string.play_sound) { _, _ ->
                if (!consumed) {
                    consumed = true
                    confirmed()
                }
            }
            .show()
    }
}
