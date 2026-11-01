package dev.findbridge.android

import android.app.Activity
import android.content.Intent

/** Chooses session reuse before password authentication; it never clears browser trust. */
internal class AppleConnectionController(
    private val activity: Activity,
    private val repository: AppleRepository,
    private val vault: LoginVault,
    private val foreground: () -> Boolean,
    private val startAuthentication: () -> Unit,
) {
    var opening = false
        private set

    fun reconnect() {
        if (opening) return
        if (repository.hasSession && !repository.requiresSignIn) {
            repository.refresh()
            return
        }
        if (vault.hasSaved && vault.automatic && !vault.paused) authenticate()
        else editCredentials()
    }

    fun editCredentials() {
        if (opening) return
        opening = true
        @Suppress("DEPRECATION")
        activity.startActivityForResult(
            Intent(activity, LoginActivity::class.java).putExtra(LoginActivity.INLINE_AUTH, true),
            REQUEST,
        )
    }

    fun maintain() {
        if (
            !foreground() ||
                !activity.hasWindowFocus() ||
                opening ||
                (repository.hasSession && !repository.requiresSignIn) ||
                !vault.automaticAllowed()
        )
            return
        authenticate()
    }

    fun completed() {
        opening = false
    }

    fun authenticatePrepared() {
        opening = true
        vault.markAttempt()
        startAuthentication()
    }

    private fun authenticate() {
        if (
            activity.isFinishing ||
                activity.isDestroyed ||
                !foreground() ||
                !activity.hasWindowFocus()
        )
            return
        opening = true
        LoginVault.clearStaged()
        vault.markAttempt()
        // Resume the existing trusted web session behind the native device list.
        startAuthentication()
    }

    companion object {
        const val REQUEST = 10
    }
}
