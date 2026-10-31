package dev.findbridge.android

import android.annotation.SuppressLint
import android.app.Activity
import android.net.http.SslError
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.findbridge.android.Ui.actionButton
import dev.findbridge.android.Ui.column
import dev.findbridge.android.Ui.dp
import dev.findbridge.android.Ui.label
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors
import org.json.JSONObject

/** Apple's official authentication screen, closed when a native session is available. */
class AuthActivity : Activity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var origin: TextView
    private lateinit var errorView: LinearLayout
    private lateinit var automaticStatus: LinearLayout
    private var captured = false
    private lateinit var vault: LoginVault
    private val loginWorker = Executors.newSingleThreadExecutor()
    private var posture: PostureObserver? = null
    private var fold: FoldRegion? = null
    private var layoutPane: () -> Unit = {}
    private val submittedStages = mutableSetOf<String>()
    private val pendingStages = mutableMapOf<String, String>()
    private val dispatchedRequests = linkedMapOf<String, String>()
    private var authGeneration = 0
    private var readyEpoch = 0
    private var imeWasVisible = false
    private var mainLoadFailed = false
    private var recoveryNeeded = false
    private var sessionRejected = false
    private var sessionObservationAvailable = false
    private var automaticInputAllowed = false
    private var resumed = false
    private val automaticScripts = mutableListOf<ScriptHandler>()
    private val loadingDeadline = AuthLoadingDeadline {
        authenticationFailed(R.string.connection_failed, R.string.apple_login_stalled)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Ui.configure(this)
        if (Ui.dark) setTheme(R.style.Theme_FindBridge_Dark)
        super.onCreate(savedInstanceState)
        vault = LoginVault(this)
        if (intent.getBooleanExtra(USE_LOGIN, false))
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        val root = column().apply { setBackgroundColor(Ui.surface) }
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val bars =
                    insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                val imeVisible = insets.isVisible(WindowInsets.Type.ime())
                if (
                    ::automaticStatus.isInitialized &&
                        automaticStatus.visibility == View.VISIBLE &&
                        imeVisible
                )
                    window.insetsController?.hide(WindowInsets.Type.ime())
                if (imeWasVisible && !imeVisible && ::webView.isInitialized)
                    webView.postOnAnimation { notifyLoginReady() }
                imeWasVisible = imeVisible
                WindowInsets.CONSUMED
            } else insets
        }
        val header =
            LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), 0, dp(14), 0)
                setBackgroundColor(Ui.surface)
            }
        header.addView(
            label(getString(R.string.cancel), 14, Ui.blue).apply {
                gravity = Gravity.CENTER
                setOnClickListener { finish() }
            },
            LinearLayout.LayoutParams(dp(88), dp(56)),
        )
        val title = column()
        title.addView(label(getString(R.string.connect_account), 15, Ui.ink, true))
        origin = label("www.icloud.com", 10, Ui.muted)
        if (intent.getBooleanExtra(USE_LOGIN, false)) origin.visibility = View.GONE
        title.addView(origin)
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(
            label("↻", 24, Ui.blue).apply {
                gravity = Gravity.CENTER
                contentDescription = getString(R.string.reload_auth)
                setOnClickListener {
                    if (restartAuthentication()) webView.reload()
                }
            },
            LinearLayout.LayoutParams(dp(48), dp(48)),
        )
        root.addView(header)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(2)))
        val frame = FrameLayout(this)
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        webView = WebView(this).apply { isSaveEnabled = false }
        webView.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) webView.postOnAnimation { notifyLoginReady() }
        }
        frame.addView(webView, FrameLayout.LayoutParams(-1, -1))
        automaticStatus =
            column().apply {
                id = AUTO_STATUS_ID
                gravity = Gravity.CENTER
                setBackgroundColor(Ui.surface)
                setPadding(dp(28), dp(24), dp(28), dp(24))
                isClickable = true
                isFocusable = true
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                addView(ProgressBar(this@AuthActivity), LinearLayout.LayoutParams(dp(36), dp(36)))
                addView(
                    label(getString(R.string.signing_in), 20, Ui.ink, true).apply {
                        setPadding(0, dp(22), 0, 0)
                    }
                )
                visibility =
                    if (intent.getBooleanExtra(USE_LOGIN, false)) View.VISIBLE else View.GONE
            }
        frame.addView(automaticStatus, FrameLayout.LayoutParams(-1, -1))
        errorView =
            column().apply {
                id = AUTH_ERROR_ID
                gravity = Gravity.CENTER
                setPadding(dp(28), dp(24), dp(28), dp(24))
                setBackgroundColor(Ui.background)
                addView(label(getString(R.string.connection_failed), 22, Ui.ink, true))
                addView(label(getString(R.string.check_connection_time), 14, Ui.muted))
                addView(
                    actionButton(getString(R.string.connect_again)).apply {
                        minimumHeight = dp(52)
                        setOnClickListener {
                            if (!restartAuthentication()) return@setOnClickListener
                            if (sessionRejected) {
                                sessionRejected = false
                                // Explicit recovery discards only transient sessions. Persistent
                                // trusted-browser cookies are kept; account changes use logout.
                                CookieManager.getInstance().removeSessionCookies {
                                    CookieManager.getInstance().flush()
                                    if (!isFinishing && !isDestroyed)
                                        webView.loadUrl(UrlPolicy.findUrl)
                                }
                            } else webView.loadUrl(UrlPolicy.findUrl)
                        }
                    },
                    LinearLayout.LayoutParams(-1, -2),
                )
                addView(
                    actionButton(getString(R.string.apple_show_sign_in), false).apply {
                        minimumHeight = dp(52)
                        id = SHOW_SIGN_IN_ID
                        setOnClickListener { revealOfficialPage() }
                    },
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) },
                )
                visibility = View.GONE
            }
        frame.addView(errorView, FrameLayout.LayoutParams(-1, -1))
        if (intent.getBooleanExtra(USE_LOGIN, false)) loadingDeadline.start()
        configureWebView()
        val surface = FrameLayout(this).apply { setBackgroundColor(Ui.surface) }
        surface.addView(root, FrameLayout.LayoutParams(-1, -1))
        layoutPane = { PostureObserver.placeInPane(surface, root, fold) }
        surface.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutPane() }
        setContentView(surface)
        if (!sessionObservationAvailable) return
        if (intent.getBooleanExtra(USE_LOGIN, false)) {
            loginWorker.execute {
                val login = LoginVault.takeStaged() ?: vault.load()
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (login != null) installAutomaticLogin(login)
                    else {
                        vault.pause()
                        revealOfficialPage()
                    }
                    webView.loadUrl(UrlPolicy.findUrl)
                }
            }
        } else webView.loadUrl(UrlPolicy.findUrl)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        installSessionObserver()
        webView.webChromeClient =
            object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, value: Int) {
                    progress.progress = value
                    progress.visibility = if (value < 100) View.VISIBLE else View.INVISIBLE
                }
            }
        webView.webViewClient =
            object : WebViewClient() {
                override fun onPageStarted(
                    view: WebView,
                    url: String,
                    favicon: android.graphics.Bitmap?,
                ) {
                    authGeneration++
                    pendingStages.clear()
                    if (!recoveryNeeded) {
                        mainLoadFailed = false
                        errorView.visibility = View.GONE
                    }
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean = !UrlPolicy.isApplePage(request.url.toString())

                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    val path = request.url.path.orEmpty()
                    return if (
                        path.startsWith("/fmipservice/client/web/") &&
                            !UrlPolicy.isReadEndpoint(request.url.toString())
                    ) {
                        WebResourceResponse(
                            "text/plain",
                            "UTF-8",
                            403,
                            "Read only",
                            emptyMap(),
                            ByteArrayInputStream(byteArrayOf()),
                        )
                    } else null
                }

                override fun onPageFinished(view: WebView, url: String) {
                    if (!mainLoadFailed) errorView.visibility = View.GONE
                    origin.text = android.net.Uri.parse(url).host ?: "www.icloud.com"
                    CookieManager.getInstance().flush()
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    val address = request.url.toString()
                    val currentMain =
                        request.isForMainFrame &&
                            address.substringBefore('#') == view.url.orEmpty().substringBefore('#')
                    // WebView also reports images and stylesheets; those must not abort sign-in.
                    val authentication =
                        UrlPolicy.isCredentialOrigin(address) &&
                            (request.url.path
                                .orEmpty()
                                .matches(Regex("/(?:appleauth/auth|auth|signin)(?:/.*)?")) ||
                                request.requestHeaders.any { (name, value) ->
                                    name.equals("Sec-Fetch-Dest", true) &&
                                        value in setOf("document", "iframe")
                                })
                    if (currentMain || authentication)
                        authenticationFailed(
                            R.string.connection_failed,
                            R.string.check_connection_time,
                        )
                }

                override fun onReceivedHttpError(
                    view: WebView,
                    request: WebResourceRequest,
                    response: WebResourceResponse,
                ) {
                    if (request.isForMainFrame)
                        authenticationFailed(
                            R.string.connection_failed,
                            R.string.check_connection_time,
                        )
                }

                override fun onReceivedSslError(
                    view: WebView,
                    handler: SslErrorHandler,
                    error: SslError,
                ) {
                    handler.cancel()
                    if (error.url == view.url) {
                        authenticationFailed(
                            R.string.connection_failed,
                            R.string.check_connection_time,
                        )
                    }
                }

                override fun onRenderProcessGone(
                    view: WebView,
                    detail: android.webkit.RenderProcessGoneDetail,
                ): Boolean {
                    Toast.makeText(
                            this@AuthActivity,
                            getString(R.string.auth_closed),
                            Toast.LENGTH_LONG,
                        )
                        .show()
                    finish()
                    return true
                }
            }
    }

    private fun installSessionObserver() {
        val installed =
            AppleSessionObserver.install(
                webView,
                assets.open("apple-session.js").bufferedReader().use { it.readText() },
                response = { data ->
                    if (
                        !captured &&
                            !isFinishing &&
                            !isDestroyed &&
                            AppleRepository.get(this)
                                .acceptAuthenticatedResponse(data, webView.settings.userAgentString)
                    ) {
                        captured = true
                        loadingDeadline.stop()
                        vault.markAuthenticated()
                        CookieManager.getInstance().flush()
                        setResult(RESULT_OK)
                        finish()
                    }
                },
                state = { state ->
                    if (!captured && !isFinishing && !isDestroyed) {
                        sessionRejected = state == "authenticationRequired"
                        authenticationFailed(
                            if (sessionRejected) R.string.sign_in_required
                            else R.string.connection_failed,
                            if (sessionRejected) R.string.apple_session_restore_failed
                            else R.string.check_connection_time,
                        )
                    }
                },
            )
        sessionObservationAvailable = installed
        if (!installed) authenticationFailed(R.string.connection_failed, R.string.update_webview)
    }

    private fun restartAuthentication(): Boolean {
        if (!sessionObservationAvailable) {
            authenticationFailed(R.string.connection_failed, R.string.update_webview)
            return false
        }
        recoveryNeeded = false
        errorView.visibility = View.GONE
        mainLoadFailed = false
        authGeneration++
        submittedStages.clear()
        pendingStages.clear()
        dispatchedRequests.clear()
        if (automaticInputAllowed) {
            automaticStatus.visibility = View.VISIBLE
            origin.visibility = View.GONE
            loadingDeadline.start()
        }
        return true
    }

    private fun authenticationFailed(title: Int, detail: Int) {
        if (captured || isFinishing || isDestroyed) return
        recoveryNeeded = true
        mainLoadFailed = true
        loadingDeadline.stop()
        authGeneration++
        pendingStages.clear()
        // Stop this document's queued input. An explicit retry gets a fresh document;
        // neither a stalled page nor a late callback may submit credentials behind an error.
        webView.evaluateJavascript("window.FindBridgeLoginRelay?.cancel()", null)
        automaticStatus.visibility = View.GONE
        progress.visibility = View.INVISIBLE
        (errorView.getChildAt(0) as TextView).setText(title)
        (errorView.getChildAt(1) as TextView).setText(detail)
        errorView.visibility = View.VISIBLE
    }

    private fun revealOfficialPage() {
        if (!sessionObservationAvailable) return
        recoveryNeeded = false
        mainLoadFailed = false
        loadingDeadline.stop()
        automaticInputAllowed = false
        automaticScripts.forEach { it.remove() }
        automaticScripts.clear()
        authGeneration++
        pendingStages.clear()
        webView.evaluateJavascript("window.FindBridgeLoginRelay?.cancel()", null)
        errorView.visibility = View.GONE
        automaticStatus.visibility = View.GONE
        origin.visibility = View.VISIBLE
        webView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
    }

    @SuppressLint("RequiresFeature")
    private fun installAutomaticLogin(login: SavedLogin) {
        automaticInputAllowed = true
        loadingDeadline.start()
        automaticStatus.visibility = View.VISIBLE
        origin.visibility = View.GONE
        // The native form already supplies the credentials. Avoid a second autofill picker
        // covering the automatic authentication window or reading its populated web fields.
        webView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        webView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        if (
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) ||
                !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        ) {
            automaticStatus.visibility = View.GONE
            origin.visibility = View.VISIBLE
            webView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            return
        }
        val origins = UrlPolicy.credentialOrigins
        WebViewCompat.addWebMessageListener(webView, "FindBridgeLoginState", origins) {
            _,
            message,
            _,
            _,
            _ ->
            when (message.data) {
                "rejected",
                "timeout" -> {
                    vault.pause()
                    authenticationFailed(R.string.sign_in_required, R.string.auto_paused)
                    Toast.makeText(this, R.string.auto_paused, Toast.LENGTH_LONG).show()
                }
                "verification" -> {
                    revealOfficialPage()
                    Toast.makeText(this, R.string.verification_help, Toast.LENGTH_LONG).show()
                }
            }
        }
        val payload =
            JSONObject().put("account", login.account).put("password", login.password).toString()
        val script =
            assets
                .open("apple-autologin.js")
                .bufferedReader()
                .use { it.readText() }
                .replace("__FINDBRIDGE_LOGIN__", payload)
        automaticScripts += WebViewCompat.addDocumentStartJavaScript(webView, script, origins)
        WebViewCompat.addWebMessageListener(
            webView,
            "FindBridgeLoginTap",
            origins + UrlPolicy.loginContainerOrigins,
        ) { _, message, sourceOrigin, isMainFrame, replyProxy ->
            if (!isMainFrame || captured || !automaticInputAllowed) return@addWebMessageListener
            if (sourceOrigin.toString().trimEnd('/') !in origins + UrlPolicy.loginContainerOrigins)
                return@addWebMessageListener
            val payload = message.data?.takeIf { it.length < 1024 } ?: return@addWebMessageListener
            val json =
                runCatching { JSONObject(payload) }.getOrNull() ?: return@addWebMessageListener
            val stage = json.optString("stage")
            val requestId = json.optString("id")
            if (stage !in setOf("account", "password") || !requestId.matches(Regex("[a-f0-9]{32}")))
                return@addWebMessageListener
            fun reply(status: String, reason: String = "") {
                val result =
                    JSONObject()
                        .put("type", "findbridge.auth.result")
                        .put("id", requestId)
                        .put("stage", stage)
                        .put("status", status)
                        .put("reason", reason)
                        .put("epoch", readyEpoch)
                        .toString()
                runCatching { replyProxy.postMessage(result) }
            }
            if (dispatchedRequests[requestId] == stage) {
                reply("dispatched")
                return@addWebMessageListener
            }
            if (stage in pendingStages) {
                reply("retry", "busy")
                return@addWebMessageListener
            }
            if (json.optString("mode") != "activate") return@addWebMessageListener
            pendingStages[stage] = requestId
            submitWhenReady(stage, requestId, ::reply)
        }
        val relay = assets.open("apple-login-relay.js").bufferedReader().use { it.readText() }
        automaticScripts +=
            WebViewCompat.addDocumentStartJavaScript(
                webView,
                relay,
                origins + UrlPolicy.loginContainerOrigins,
            )
        val landing = assets.open("apple-login-landing.js").bufferedReader().use { it.readText() }
        automaticScripts +=
            WebViewCompat.addDocumentStartJavaScript(
                webView,
                landing,
                UrlPolicy.loginContainerOrigins,
            )
    }

    /** Activates the focused button with trusted native input after focus and layout are ready. */
    private fun submitWhenReady(
        stage: String,
        requestId: String,
        reply: (String, String) -> Unit,
        visualReady: Boolean = false,
    ) {
        val generation = authGeneration
        if (!visualReady) {
            // DOM readiness precedes Chromium's input/compositor state, especially
            // for nested frames. Wait for that completion event, never a duration.
            webView.postVisualStateCallback(
                generation.toLong(),
                object : WebView.VisualStateCallback() {
                    override fun onComplete(id: Long) {
                        if (authGeneration == generation)
                            submitWhenReady(stage, requestId, reply, visualReady = true)
                        else {
                            if (pendingStages[stage] == requestId) pendingStages.remove(stage)
                            reply("retry", "closed")
                        }
                    }
                },
            )
            return
        }
        val queuedAt = SystemClock.uptimeMillis()
        fun finish(status: String, reason: String = "") {
            if (pendingStages[stage] == requestId) pendingStages.remove(stage)
            reply(status, reason)
        }
        webView.postOnAnimation {
            if (isFinishing || isDestroyed || captured || authGeneration != generation) {
                finish("retry", "closed")
                return@postOnAnimation
            }
            if (!window.decorView.hasWindowFocus()) {
                finish("waiting", "focus")
                return@postOnAnimation
            }
            if (SystemClock.uptimeMillis() - queuedAt > 750) {
                finish("retry", "viewport")
                return@postOnAnimation
            }
            if (
                webView.width == 0 ||
                    webView.isLayoutRequested ||
                    errorView.visibility == View.VISIBLE
            ) {
                finish("retry", "viewport")
                return@postOnAnimation
            }
            if (
                android.os.Build.VERSION.SDK_INT >= 30 &&
                    webView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
            ) {
                window.insetsController?.hide(WindowInsets.Type.ime())
                finish("waiting", "viewport")
                return@postOnAnimation
            }
            // Acquiring native focus can reset the focused element in a child document.
            // Let the script focus its button again after that transition, before input.
            if (!webView.hasFocus()) {
                webView.requestFocus()
                finish("retry", "focus")
                return@postOnAnimation
            }
            val down = SystemClock.uptimeMillis()
            fun dispatch(action: Int, cancelled: Boolean = false) {
                val event =
                    KeyEvent(
                        down,
                        SystemClock.uptimeMillis(),
                        action,
                        KeyEvent.KEYCODE_ENTER,
                        0,
                        0,
                        KeyCharacterMap.VIRTUAL_KEYBOARD,
                        0,
                        if (cancelled) KeyEvent.FLAG_CANCELED else 0,
                        InputDevice.SOURCE_KEYBOARD,
                    )
                webView.dispatchKeyEvent(event)
            }
            dispatch(KeyEvent.ACTION_DOWN)
            webView.postOnAnimation up@{
                if (isFinishing || isDestroyed) {
                    finish("retry", "closed")
                    return@up
                }
                if (authGeneration != generation || !window.decorView.hasWindowFocus()) {
                    dispatch(KeyEvent.ACTION_UP, true)
                    finish("waiting", "focus")
                    return@up
                }
                dispatch(KeyEvent.ACTION_UP)
                submittedStages += stage
                if (dispatchedRequests.size >= 32)
                    dispatchedRequests.remove(dispatchedRequests.keys.first())
                dispatchedRequests[requestId] = stage
                finish("dispatched")
            }
        }
    }

    private fun notifyLoginReady() {
        if (
            !::webView.isInitialized ||
                isFinishing ||
                isDestroyed ||
                !window.decorView.hasWindowFocus()
        )
            return
        readyEpoch++
        webView.evaluateJavascript("window.FindBridgeLoginRelay?.resume($readyEpoch)", null)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!::webView.isInitialized) return
        loadingDeadline.setActive(resumed && hasFocus)
        if (hasFocus) webView.postOnAnimation { notifyLoginReady() }
        else webView.evaluateJavascript("window.FindBridgeLoginRelay?.suspend()", null)
    }

    override fun onPause() {
        resumed = false
        loadingDeadline.setActive(false)
        webView.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
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

    override fun onResume() {
        super.onResume()
        resumed = true
        loadingDeadline.setActive(hasWindowFocus())
        webView.onResume()
    }

    override fun onDestroy() {
        if (isFinishing && !captured && intent.getBooleanExtra(USE_LOGIN, false)) vault.pause()
        loadingDeadline.stop()
        automaticScripts.forEach { it.remove() }
        automaticScripts.clear()
        loginWorker.shutdown()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.webChromeClient = null
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        const val USE_LOGIN = "useLocalLogin"
        const val AUTO_STATUS_ID = 1201
        const val AUTH_ERROR_ID = 1202
        const val SHOW_SIGN_IN_ID = 1203
    }
}
