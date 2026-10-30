package dev.findbridge.android

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import dev.findbridge.android.Ui.actionButton
import dev.findbridge.android.Ui.column
import dev.findbridge.android.Ui.dp
import dev.findbridge.android.Ui.label

/**
 * GMS-to-Web session handoff in the isolated Google profile; only required verification is visible.
 */
class GoogleWebSessionActivity : Activity() {
    override fun attachBaseContext(newBase: android.content.Context) =
        super.attachBaseContext(AppLanguage.wrap(newBase))

    private lateinit var session: GoogleSession
    private var dataCallback: ((String) -> Unit)? = null
    private var errorCallback: (() -> Unit)? = null
    private var posture: PostureObserver? = null
    private var fold: FoldRegion? = null
    private var layoutPane: () -> Unit = {}
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var resumed = false
    private var recovery: Intent? = null
    private var attempts = 0
    private var handoffAttempted = false
    private var handoffLoading = false
    private var expectedAccount = ""
    private var browser: android.webkit.WebView? = null
    private var retryWeb: () -> Unit = {}

    override fun onCreate(savedInstanceState: Bundle?) {
        Ui.configure(this)
        if (Ui.dark) setTheme(R.style.Theme_FindBridge_Dark)
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        session = GoogleSession.get(this)
        val root = column().apply { setBackgroundColor(Ui.surface) }
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val bars =
                    insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                WindowInsets.CONSUMED
            } else insets
        }
        val header =
            LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18), 0, dp(18), 0)
            }
        header.addView(
            label(getString(R.string.cancel), 15, Ui.blue).apply {
                setOnClickListener { finish() }
            },
            LinearLayout.LayoutParams(dp(88), dp(56)),
        )
        val title = column()
        title.addView(label(getString(R.string.google_connect), 15, Ui.ink, true))
        val origin = label("accounts.google.com", 10, Ui.muted)
        title.addView(origin)
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(header)
        val frame = FrameLayout(this)
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        val loading =
            column().apply {
                gravity = Gravity.CENTER
                setPadding(dp(28), dp(24), dp(28), dp(24))
                setBackgroundColor(Ui.surface)
                isClickable = true
                isFocusable = true
                addView(ProgressBar(this@GoogleWebSessionActivity))
                addView(
                    label(getString(R.string.google_loading), 17, Ui.ink).apply {
                        gravity = Gravity.CENTER
                        setPadding(0, dp(20), 0, dp(12))
                    }
                )
                addView(label(getString(R.string.google_auth_help), 13, Ui.muted))
            }
        frame.addView(loading, FrameLayout.LayoutParams(-1, -1))
        fun page(url: String) {
            origin.text = android.net.Uri.parse(url).host.orEmpty()
            // Find Hub's remote-control buttons are never exposed for interaction.
            if (
                GoogleProtocol.isDataPage(url) ||
                    android.net.Uri.parse(url).path in
                        setOf("/accounts/SetSID", "/accounts/CheckCookie")
            )
                loading.visibility = View.VISIBLE
            if (!handoffAttempted && url.contains("/android/find/about")) requestHandoff()
            if (
                handoffAttempted &&
                    !handoffLoading &&
                    browser?.url == url &&
                    url.contains("/android/find/about")
            )
                browser?.evaluateJavascript(
                    "(()=>{const a=[...document.querySelectorAll('a[href]')].find(e=>{try{return new URL(e.href).hostname==='accounts.google.com'}catch(_){return false}});if(a){const u=new URL(a.href);u.searchParams.set('continue','https://www.google.com/android/find/');u.searchParams.set('followup','https://www.google.com/android/find/');location.assign(u.href);}})()",
                    null,
                )
        }
        session.onPage = ::page
        session.onVerification = { required ->
            if (required && !handoffAttempted) requestHandoff()
            else if (handoffLoading) loading.visibility = View.VISIBLE
            else if (!required || handoffAttempted)
                loading.visibility = if (required) View.GONE else View.VISIBLE
        }
        dataCallback = { snapshot ->
            // Authentication ends as soon as the real catalog is available. Position
            // enrichment belongs to the native screen's bound refresh, not this cover.
            val record = org.json.JSONObject(snapshot)
            val account = record.optString("account")
            if (
                account.isNotBlank() &&
                    expectedAccount.isNotBlank() &&
                    !account.equals(expectedAccount, true)
            ) {
                errorCallback?.invoke()
            } else if (
                account.isNotBlank() &&
                    account.equals(expectedAccount, true) &&
                    (record.optJSONArray("devices")?.length() ?: 0) > 0
            ) {
                setResult(RESULT_OK, Intent().putExtra(SNAPSHOT, snapshot))
                finish()
            }
        }
        session.onData = dataCallback
        errorCallback = {
            loading.visibility = View.VISIBLE
            loading.removeAllViews()
            loading.addView(label(getString(R.string.google_sign_in_needed), 17, Ui.ink))
            loading.addView(
                actionButton(getString(R.string.connect_again)).apply {
                    setOnClickListener { retryWeb() }
                }
            )
        }
        session.onError = errorCallback
        fun showLoading() {
            loading.visibility = View.VISIBLE
            loading.removeAllViews()
            loading.addView(ProgressBar(this))
            loading.addView(
                label(getString(R.string.google_loading), 17, Ui.ink).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(20), 0, dp(12))
                }
            )
            loading.addView(label(getString(R.string.google_auth_help), 13, Ui.muted))
        }
        retryWeb = {
            showLoading()
            handoffAttempted = false
            handoffLoading = false
            attempts = 0
            worker.execute {
                val identity =
                    contentResolver.call(
                        android.net.Uri.parse(
                            "content://${BuildConfig.APPLICATION_ID}.googleidentity"
                        ),
                        "verifiedIdentity",
                        null,
                        null,
                    )
                val name = identity?.getString("account").orEmpty()
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        expectedAccount = name
                        if (identity?.getBoolean("verified") != true || name.isBlank())
                            errorCallback?.invoke()
                        else {
                            // A completed owner login can precede the catalog callback.
                            // Always try this private profile before requesting GMS renewal.
                            val web = session.attach(this)
                            browser = web
                            frame.addView(web, 0, FrameLayout.LayoutParams(-1, -1))
                        }
                    }
                }
            }
        }
        retryWeb()
        val surface = FrameLayout(this).apply { setBackgroundColor(Ui.surface) }
        surface.addView(root, FrameLayout.LayoutParams(-1, -1))
        layoutPane = { PostureObserver.placeInPane(surface, root, fold) }
        surface.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutPane() }
        setContentView(surface)
    }

    override fun onStart() {
        super.onStart()
        posture =
            PostureObserver(this) {
                    fold = it
                    layoutPane()
                }
                .also { it.start() }
    }

    override fun onStop() {
        posture?.stop()
        posture = null
        super.onStop()
    }

    override fun onDestroy() {
        if (session.onData === dataCallback) session.onData = null
        if (session.onError === errorCallback) session.onError = null
        session.detach()
        worker.shutdown()
        recovery = null
        super.onDestroy()
    }

    private fun requestHandoff() {
        handoffAttempted = true
        handoffLoading = true
        attempts++
        worker.execute {
            val identity =
                contentResolver.call(
                    android.net.Uri.parse("content://${BuildConfig.APPLICATION_ID}.googleidentity"),
                    "verifiedIdentity",
                    null,
                    null,
                )
            val name = identity?.getString("account").orEmpty()
            if (identity?.getBoolean("verified") != true || name.isBlank()) {
                runOnUiThread { errorCallback?.invoke() }
                return@execute
            }
            expectedAccount = name
            try {
                @Suppress("DEPRECATION")
                val loginUrl =
                    com.google.android.gms.auth.GoogleAuthUtil.getToken(
                        this,
                        android.accounts.Account(name, "com.google"),
                        "weblogin:continue=https://www.google.com/android/find/",
                    )
                val uri = java.net.URI(loginUrl)
                check(
                    uri.scheme == "https" &&
                        uri.host == "accounts.google.com" &&
                        uri.path == "/MergeSession" &&
                        uri.userInfo == null &&
                        uri.port in listOf(-1, 443)
                )
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    handoffAttempted = true
                    handoffLoading = false
                    browser?.stopLoading()
                    browser?.loadUrl(loginUrl)
                }
            } catch (failure: com.google.android.gms.auth.UserRecoverableAuthException) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (attempts > 1) errorCallback?.invoke()
                    else {
                        recovery = failure.intent
                        launchRecovery()
                    }
                }
            } catch (_: Exception) {
                runOnUiThread { if (!isFinishing && !isDestroyed) errorCallback?.invoke() }
            }
        }
    }

    private fun launchRecovery() {
        if (!resumed || !hasWindowFocus() || isFinishing || isDestroyed) return
        val intent = recovery ?: return
        recovery = null
        @Suppress("DEPRECATION") startActivityForResult(intent, 73)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        launchRecovery()
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) launchRecovery()
    }

    @Deprecated("Activity result compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 73) {
            if (resultCode == RESULT_OK) requestHandoff() else errorCallback?.invoke()
        }
    }

    companion object {
        const val SNAPSHOT = "googleDevices"
    }
}
