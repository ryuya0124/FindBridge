package dev.findbridge.android

import android.annotation.SuppressLint
import android.app.Activity
import android.net.http.SslError
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
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
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.findbridge.android.Ui.actionButton
import dev.findbridge.android.Ui.column
import dev.findbridge.android.Ui.dp
import dev.findbridge.android.Ui.label
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors
import org.json.JSONObject

/** The only WebView in the app: Apple's authentication screen, closed when data is available. */
class AuthActivity : Activity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var origin: TextView
    private lateinit var errorView: LinearLayout
    private var captured = false
    private lateinit var vault: LoginVault
    private val loginWorker = Executors.newSingleThreadExecutor()
    private var posture: PostureObserver? = null
    private var fold: FoldRegion? = null
    private var layoutPane: () -> Unit = {}
    private val submittedStages = mutableSetOf<String>()
    private val pendingStages = mutableSetOf<String>()
    private var mainLoadFailed = false

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
        title.addView(origin)
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(
            label("↻", 24, Ui.blue).apply {
                gravity = Gravity.CENTER
                contentDescription = getString(R.string.reload_auth)
                setOnClickListener {
                    errorView.visibility = View.GONE
                    submittedStages.clear()
                    webView.reload()
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
        frame.addView(webView, FrameLayout.LayoutParams(-1, -1))
        errorView =
            column().apply {
                gravity = Gravity.CENTER
                setPadding(dp(28), dp(24), dp(28), dp(24))
                setBackgroundColor(Ui.background)
                addView(label(getString(R.string.connection_failed), 22, Ui.ink, true))
                addView(label(getString(R.string.check_connection_time), 14, Ui.muted))
                addView(
                    actionButton(getString(R.string.connect_again)).apply {
                        setOnClickListener {
                            errorView.visibility = View.GONE
                            submittedStages.clear()
                            webView.loadUrl(UrlPolicy.findUrl)
                        }
                    },
                    LinearLayout.LayoutParams(-1, dp(52)),
                )
                visibility = View.GONE
            }
        frame.addView(errorView, FrameLayout.LayoutParams(-1, -1))
        configureWebView()
        val surface = FrameLayout(this).apply { setBackgroundColor(Ui.surface) }
        surface.addView(root, FrameLayout.LayoutParams(-1, -1))
        layoutPane = { PostureObserver.placeInPane(surface, root, fold) }
        surface.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutPane() }
        setContentView(surface)
        if (intent.getBooleanExtra(USE_LOGIN, false)) {
            loginWorker.execute {
                val login = LoginVault.takeStaged() ?: vault.load()
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (login != null) installAutomaticLogin(login) else vault.pause()
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
                    mainLoadFailed = false
                    errorView.visibility = View.GONE
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
                    if (request.isForMainFrame) {
                        mainLoadFailed = true
                        errorView.visibility = View.VISIBLE
                    }
                }

                override fun onReceivedSslError(
                    view: WebView,
                    handler: SslErrorHandler,
                    error: SslError,
                ) {
                    handler.cancel()
                    if (error.url == view.url) {
                        mainLoadFailed = true
                        errorView.visibility = View.VISIBLE
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

    @SuppressLint("RequiresFeature")
    private fun installSessionObserver() {
        if (
            !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
                !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        ) {
            Toast.makeText(this, getString(R.string.update_webview), Toast.LENGTH_LONG).show()
            return
        }
        val origins = setOf("https://www.icloud.com", "https://www.icloud.com.cn")
        WebViewCompat.addWebMessageListener(webView, "FindBridgeData", origins) {
            _,
            message,
            _,
            _,
            _ ->
            val data = message.data
            if (
                !captured &&
                    data != null &&
                    AppleRepository.get(this)
                        .acceptAuthenticatedResponse(data, webView.settings.userAgentString)
            ) {
                captured = true
                vault.markSuccess()
                CookieManager.getInstance().flush()
                setResult(RESULT_OK)
                finish()
            }
        }
        val script = assets.open("apple-session.js").bufferedReader().use { it.readText() }
        WebViewCompat.addDocumentStartJavaScript(webView, script, origins)
    }

    @SuppressLint("RequiresFeature")
    private fun installAutomaticLogin(login: SavedLogin) {
        if (
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) ||
                !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        )
            return
        val origins = UrlPolicy.credentialOrigins
        WebViewCompat.addWebMessageListener(webView, "FindBridgeLoginState", origins) {
            _,
            message,
            _,
            _,
            _ ->
            when (message.data) {
                "rejected" -> {
                    vault.pause()
                    Toast.makeText(this, R.string.auto_paused, Toast.LENGTH_LONG).show()
                }
                "verification" ->
                    Toast.makeText(this, R.string.verification_help, Toast.LENGTH_LONG).show()
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
        WebViewCompat.addDocumentStartJavaScript(webView, script, origins)
        WebViewCompat.addWebMessageListener(
            webView,
            "FindBridgeLoginTap",
            origins + UrlPolicy.loginContainerOrigins,
        ) { _, message, sourceOrigin, isMainFrame, _ ->
            if (!isMainFrame || captured) return@addWebMessageListener
            if (sourceOrigin.toString().trimEnd('/') !in origins + UrlPolicy.loginContainerOrigins)
                return@addWebMessageListener
            val payload = message.data?.takeIf { it.length < 1024 } ?: return@addWebMessageListener
            val json =
                runCatching { JSONObject(payload) }.getOrNull() ?: return@addWebMessageListener
            val stage = json.optString("stage")
            if (
                stage !in setOf("account", "password") ||
                    stage in submittedStages ||
                    stage in pendingStages
            )
                return@addWebMessageListener
            val viewportWidth = json.optDouble("width")
            val x = json.optDouble("x") / viewportWidth
            val y = json.optDouble("y") / viewportWidth
            if (
                !viewportWidth.isFinite() ||
                    viewportWidth <= 0 ||
                    !x.isFinite() ||
                    !y.isFinite() ||
                    x !in 0.0..1.0 ||
                    y !in 0.0..8.0
            )
                return@addWebMessageListener
            pendingStages += stage
            submitWhenReady(stage, x, y)
        }
        val landing = assets.open("apple-login-landing.js").bufferedReader().use { it.readText() }
        WebViewCompat.addDocumentStartJavaScript(
            webView,
            landing,
            UrlPolicy.loginContainerOrigins,
        )
    }

    private fun submitWhenReady(
        stage: String,
        relativeX: Double,
        relativeY: Double,
        attempt: Int = 0,
    ) {
        webView.postDelayed(
            {
                if (isFinishing || isDestroyed || captured) {
                    pendingStages.remove(stage)
                    return@postDelayed
                }
                if (
                    !window.decorView.hasWindowFocus() ||
                        webView.width == 0 ||
                        errorView.visibility == View.VISIBLE
                ) {
                    if (attempt < 40) submitWhenReady(stage, relativeX, relativeY, attempt + 1)
                    else {
                        pendingStages.remove(stage)
                        vault.pause()
                    }
                    return@postDelayed
                }
                val x = (relativeX * webView.width).toFloat()
                val y = (relativeY * webView.width).toFloat()
                if (y < 0 || y > webView.height) {
                    pendingStages.remove(stage)
                    return@postDelayed
                }
                pendingStages.remove(stage)
                submittedStages += stage
                val down = SystemClock.uptimeMillis()
                fun dispatch(action: Int) {
                    if (isFinishing || isDestroyed) return
                    val event =
                        MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                    webView.dispatchTouchEvent(event)
                    event.recycle()
                }
                webView.requestFocus()
                dispatch(MotionEvent.ACTION_DOWN)
                webView.postDelayed({ dispatch(MotionEvent.ACTION_UP) }, 70)
            },
            if (attempt == 0) 240L else 100L,
        )
    }

    override fun onPause() {
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
        webView.onResume()
    }

    override fun onDestroy() {
        if (isFinishing && !captured && intent.getBooleanExtra(USE_LOGIN, false)) vault.pause()
        loginWorker.shutdown()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.webChromeClient = null
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        const val USE_LOGIN = "useLocalLogin"
    }
}
