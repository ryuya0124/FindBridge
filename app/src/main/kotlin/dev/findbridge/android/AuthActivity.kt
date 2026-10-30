package dev.findbridge.android

import android.annotation.SuppressLint
import android.app.Activity
import android.net.http.SslError
import android.os.Bundle
import android.view.Gravity
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

/** The only WebView in the app: Apple's authentication screen, closed when data is available. */
class AuthActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var origin: TextView
    private lateinit var errorView: LinearLayout
    private var captured = false

    override fun onCreate(savedInstanceState: Bundle?) {
        Ui.configure(this)
        if (Ui.dark) setTheme(R.style.Theme_FindBridge_Dark)
        super.onCreate(savedInstanceState)
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
            label("キャンセル", 14, Ui.blue).apply {
                gravity = Gravity.CENTER
                setOnClickListener { finish() }
            },
            LinearLayout.LayoutParams(dp(88), dp(56)),
        )
        val title = column()
        title.addView(label("Apple Accountに接続", 15, Ui.ink, true))
        origin = label("www.icloud.com", 10, Ui.muted)
        title.addView(origin)
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(
            label("↻", 24, Ui.blue).apply {
                gravity = Gravity.CENTER
                contentDescription = "認証ページを再読み込み"
                setOnClickListener {
                    errorView.visibility = View.GONE
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
        webView = WebView(this)
        frame.addView(webView, FrameLayout.LayoutParams(-1, -1))
        errorView =
            column().apply {
                gravity = Gravity.CENTER
                setPadding(dp(28), dp(24), dp(28), dp(24))
                setBackgroundColor(Ui.background)
                addView(label("接続できませんでした", 22, Ui.ink, true))
                addView(label("接続や端末の日時を確認してください。", 14, Ui.muted))
                addView(
                    actionButton("もう一度接続").apply {
                        setOnClickListener {
                            errorView.visibility = View.GONE
                            webView.loadUrl(UrlPolicy.findUrl)
                        }
                    },
                    LinearLayout.LayoutParams(-1, dp(52)),
                )
                visibility = View.GONE
            }
        frame.addView(errorView, FrameLayout.LayoutParams(-1, -1))
        configureWebView()
        setContentView(root)
        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null)
            webView.loadUrl(UrlPolicy.findUrl)
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
                    origin.text = android.net.Uri.parse(url).host ?: "www.icloud.com"
                    CookieManager.getInstance().flush()
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    if (request.isForMainFrame) errorView.visibility = View.VISIBLE
                }

                override fun onReceivedSslError(
                    view: WebView,
                    handler: SslErrorHandler,
                    error: SslError,
                ) {
                    handler.cancel()
                    errorView.visibility = View.VISIBLE
                }

                override fun onRenderProcessGone(
                    view: WebView,
                    detail: android.webkit.RenderProcessGoneDetail,
                ): Boolean {
                    Toast.makeText(this@AuthActivity, "認証画面を閉じました。もう一度接続してください。", Toast.LENGTH_LONG)
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
            Toast.makeText(this, "Android System WebViewを更新してください", Toast.LENGTH_LONG).show()
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
                CookieManager.getInstance().flush()
                setResult(RESULT_OK)
                finish()
            }
        }
        val script = assets.open("apple-session.js").bufferedReader().use { it.readText() }
        WebViewCompat.addDocumentStartJavaScript(webView, script, origins)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onPause() {
        webView.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onDestroy() {
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.webChromeClient = null
        webView.destroy()
        super.onDestroy()
    }
}
