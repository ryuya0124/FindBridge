package dev.findbridge.android

import android.app.Activity
import android.os.Bundle
import android.webkit.WebView

/** Explicit official authentication; automatic connections use the native list instead. */
class AuthActivity : Activity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    private lateinit var flow: AppleAuthFlow
    // Kept as direct fields for existing fixture and diagnostic tooling.
    private lateinit var webView: WebView
    private lateinit var submittedStages: MutableSet<String>
    private lateinit var pendingStages: MutableMap<String, String>
    private var secureWindow: SecureWindowLease? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        Ui.configure(this)
        if (Ui.dark) setTheme(R.style.Theme_FindBridge_Dark)
        super.onCreate(savedInstanceState)
        val automatic = intent.getBooleanExtra(USE_LOGIN, false)
        if (automatic) secureWindow = SecureWindowLease.acquire(window)
        flow =
            AppleAuthFlow(
                this,
                automatic,
                changed = {},
                finished = { success ->
                    if (success) setResult(RESULT_OK)
                    finish()
                },
            )
        webView = flow.webView
        submittedStages = flow.submittedStages
        pendingStages = flow.pendingStages
        setContentView(flow.view)
        flow.start()
    }

    private fun installAutomaticLogin(login: SavedLogin) = flow.installAutomaticLogin(login)

    override fun onStart() {
        super.onStart()
        flow.onStart()
    }

    override fun onResume() {
        super.onResume()
        flow.onResume()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (::flow.isInitialized) flow.onWindowFocusChanged(hasFocus)
    }

    override fun onPause() {
        flow.onPause()
        super.onPause()
    }

    override fun onStop() {
        flow.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        flow.dispose(pauseAutomatic = isFinishing)
        secureWindow?.close()
        super.onDestroy()
    }

    companion object {
        const val USE_LOGIN = "useLocalLogin"
        const val AUTO_STATUS_ID = 1201
        const val AUTH_ERROR_ID = 1202
        const val SHOW_SIGN_IN_ID = 1203
    }
}
