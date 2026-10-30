package dev.findbridge.android

import android.app.Activity
import android.app.AlertDialog

/** The request callback runs only after the owner presses the explicit playback action. */
internal object SoundConfirmation {
    fun show(activity: Activity, name: String, confirmed: () -> Unit): AlertDialog =
        AlertDialog.Builder(activity)
            .setTitle(R.string.play_sound)
            .setMessage(activity.getString(R.string.sound_confirmation, name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.play_sound) { _, _ -> confirmed() }
            .show()
}
