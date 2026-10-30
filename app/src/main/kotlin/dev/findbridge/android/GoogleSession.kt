package dev.findbridge.android

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

/** Runs exclusively in :google. Its WebView cookie profile never touches Apple's profile. */
internal class GoogleSession private constructor(context: Context) {
    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var wrapper: MutableContextWrapper? = null
    private var web: WebView? = null
    private var attached = false
    private var requestedIndex = -1
    private var refreshAfterDetach: Int? = null
    private var command: JSONObject? = null
    private var commandGranted = false
    private var commandReady = false
    private var commandCommitReady = false
    private var commandCommitGranted = false
    private var commandGrantPending = false
    private var commandPage = 0
    private val commandWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
    var onCommandResult: ((String) -> Unit)? = null
    var commandForeground: (() -> Boolean)? = null
    var snapshot: String? = null
        private set

    var onData: ((String) -> Unit)? = null
    var onError: (() -> Unit)? = null
    var onPage: ((String) -> Unit)? = null
    var onVerification: ((Boolean) -> Unit)? = null
    var onBlockedNavigation: ((String, String) -> Unit)? = null

    private fun debugPage(view: WebView, stage: String) {
        if (!BuildConfig.DEBUG) return
        val uri = android.net.Uri.parse(view.url.orEmpty())
        val route =
            uri.path
                .orEmpty()
                .replace(
                    Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE),
                    "<account>",
                )
                .take(200)
        val state =
            JSONObject().put("stage", stage).put("host", uri.host.orEmpty()).put("path", route)
        java.io.File(app.filesDir, "google-web-state.json").writeText(state.toString())
        if (stage != "finished") return
        view.evaluateJavascript(
            "JSON.stringify({ready:document.readyState,reader:!!window.FindBridgeGoogleReader,indexedButtons:document.querySelectorAll('[role=button][data-index]').length,avatarHasEmail:!!document.querySelector('[aria-label*=\"@\"][role=button],button[aria-label*=\"@\"],a[aria-label*=\"@\"]'),markerSlots:document.querySelectorAll('[data-location-lat][data-location-lng][data-slot-id]').length})"
        ) { encoded ->
            runCatching {
                state.put("dom", JSONObject(org.json.JSONTokener(encoded).nextValue().toString()))
                java.io.File(app.filesDir, "google-web-state.json").writeText(state.toString())
            }
        }
    }

    private val timeout = Runnable {
        onError?.invoke()
        if (!attached) destroyWeb()
    }

    @SuppressLint("SetJavaScriptEnabled", "RequiresFeature")
    private fun create(): WebView {
        web?.let {
            return it
        }
        check(android.app.Application.getProcessName().endsWith(":google"))
        // Must precede CookieManager and every WebView in this process.
        if (!profileInitialized) {
            WebView.setDataDirectorySuffix("googleFindHub")
            profileInitialized = true
        }
        wrapper = MutableContextWrapper(app)
        return WebView(wrapper!!).also { view ->
            web = view
            view.isSaveEnabled = false
            view.isSoundEffectsEnabled = false
            // A short-lived bound refresh also needs a layout viewport without an Activity.
            // The visible sign-in host subsequently supplies its own measured dimensions.
            val metrics = app.resources.displayMetrics
            view.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(
                    metrics.widthPixels,
                    android.view.View.MeasureSpec.EXACTLY,
                ),
                android.view.View.MeasureSpec.makeMeasureSpec(
                    metrics.heightPixels,
                    android.view.View.MeasureSpec.EXACTLY,
                ),
            )
            view.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
            view.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                mediaPlaybackRequiresUserGesture = true
                setSupportMultipleWindows(false)
            }
            WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
            CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
            check(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
            check(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
            if (BuildConfig.DEBUG) {
                WebViewCompat.addWebMessageListener(
                    view,
                    "FindBridgeGoogleDebug",
                    GoogleProtocol.dataOrigins,
                ) { _, message, origin, mainFrame, _ ->
                    if (
                        mainFrame &&
                            origin.toString().trimEnd('/') in GoogleProtocol.dataOrigins &&
                            (message.data?.length ?: 0) <= 8000
                    ) {
                        runCatching {
                            java.io
                                .File(app.filesDir, "google-web-dom-state.json")
                                .writeText(JSONObject(message.data!!).toString())
                        }
                    }
                }
                WebViewCompat.addDocumentStartJavaScript(
                    view,
                    app.assets.open("google-debug-state.js").bufferedReader().use { it.readText() },
                    GoogleProtocol.dataOrigins,
                )
            }
            WebViewCompat.addWebMessageListener(
                view,
                "FindBridgeGoogleActionState",
                GoogleProtocol.dataOrigins,
            ) { _, message, origin, mainFrame, _ ->
                val operation = command ?: return@addWebMessageListener
                if (
                    !mainFrame ||
                        origin.toString().trimEnd('/') !in GoogleProtocol.dataOrigins ||
                        !GoogleProtocol.isDataPage(view.url.orEmpty()) ||
                        (message.data?.length ?: 0) > 4096
                )
                    return@addWebMessageListener
                val state =
                    runCatching { JSONObject(message.data!!) }.getOrNull()
                        ?: return@addWebMessageListener
                if (state.optString("ticket") != operation.optString("ticket"))
                    return@addWebMessageListener
                val status = state.optString("status")
                if (status == "ready") {
                    commandReady = true
                    resumeCommand()
                } else if (status == "commitReady" && operation.optString("type") == "MARK_LOST") {
                    commandCommitReady = true
                    resumeCommand()
                } else if (
                    status in
                        setOf(
                            "REQUESTED",
                            "UNKNOWN",
                            "CANCELED",
                            "UNAVAILABLE",
                            "ACCOUNT_MISMATCH",
                            "TARGET_CHANGED",
                        )
                ) {
                    handler.removeCallbacks(timeout)
                    onCommandResult?.invoke(status)
                }
            }
            WebViewCompat.addWebMessageListener(
                view,
                "FindBridgeGoogleState",
                GoogleProtocol.dataOrigins,
            ) { _, message, origin, mainFrame, _ ->
                if (
                    !mainFrame ||
                        origin.toString().trimEnd('/') !in GoogleProtocol.dataOrigins ||
                        !GoogleProtocol.isDataPage(view.url.orEmpty())
                )
                    return@addWebMessageListener
                val verification = message.data == "verification"
                if (verification) handler.removeCallbacks(timeout)
                else if (message.data == "loading") {
                    handler.removeCallbacks(timeout)
                    handler.postDelayed(timeout, 100_000)
                } else return@addWebMessageListener
                if (attached) onVerification?.invoke(verification)
                else if (verification) {
                    onError?.invoke()
                    handler.post { destroyWeb() }
                }
            }
            WebViewCompat.addWebMessageListener(
                view,
                "FindBridgeGoogleData",
                GoogleProtocol.dataOrigins,
            ) { _, message, origin, mainFrame, _ ->
                if (!mainFrame || origin.toString().trimEnd('/') !in GoogleProtocol.dataOrigins)
                    return@addWebMessageListener
                val text =
                    message.data?.takeIf { it.length <= GoogleProtocol.MAX_SNAPSHOT_CHARS }
                        ?: return@addWebMessageListener
                val json =
                    runCatching { JSONObject(text) }.getOrNull() ?: return@addWebMessageListener
                if (!json.optBoolean("verified") || !GoogleProtocol.isDataPage(view.url.orEmpty()))
                    return@addWebMessageListener
                if (runCatching { GoogleProtocol.devices(json) }.isFailure)
                    return@addWebMessageListener
                if (BuildConfig.DEBUG) {
                    val records = GoogleProtocol.devices(json)
                    java.io
                        .File(app.filesDir, "google-web-catalog-state.json")
                        .writeText(
                            JSONObject()
                                .put("complete", json.optBoolean("complete"))
                                .put("accountPresent", json.optString("account").isNotBlank())
                                .put("devices", records.size)
                                .put("located", records.count { it.located })
                                .put(
                                    "imageSources",
                                    org.json.JSONArray().apply {
                                        val source = json.optJSONArray("devices")
                                        for (index in 0 until (source?.length() ?: 0)) {
                                            val uri =
                                                android.net.Uri.parse(
                                                    source!!
                                                        .getJSONObject(index)
                                                        .optString("imageUrl")
                                                )
                                            put(
                                                JSONObject()
                                                    .put("scheme", uri.scheme.orEmpty())
                                                    .put("host", uri.host.orEmpty())
                                                    .put(
                                                        "extension",
                                                        uri.path
                                                            .orEmpty()
                                                            .substringAfterLast('.', "")
                                                            .takeIf {
                                                                it.matches(
                                                                    Regex("[A-Za-z0-9]{1,8}")
                                                                )
                                                            }
                                                            .orEmpty(),
                                                    )
                                            )
                                        }
                                    },
                                )
                                .toString()
                        )
                }
                snapshot = text
                CookieManager.getInstance().flush()
                onData?.invoke(text)
                if (json.optBoolean("complete")) {
                    handler.removeCallbacks(timeout)
                    if (!attached) handler.post { destroyWeb() }
                }
            }
            WebViewCompat.addWebMessageListener(
                view,
                "FindBridgeGoogleAuthState",
                setOf("https://accounts.google.com"),
            ) { _, message, origin, mainFrame, _ ->
                if (
                    mainFrame &&
                        origin.toString().trimEnd('/') == "https://accounts.google.com" &&
                        android.net.Uri.parse(view.url.orEmpty()).host == "accounts.google.com" &&
                        message.data == "verification"
                ) {
                    handler.removeCallbacks(timeout)
                    if (attached) onVerification?.invoke(true)
                    else {
                        onError?.invoke()
                        handler.post { destroyWeb() }
                    }
                }
            }
            WebViewCompat.addDocumentStartJavaScript(
                view,
                app.assets.open("google-auth-state.js").bufferedReader().use { it.readText() },
                setOf("https://accounts.google.com"),
            )
            val script =
                if (command != null) {
                    app.assets
                        .open("google-device-action.js")
                        .bufferedReader()
                        .use { it.readText() }
                        .replace(
                            "__FINDBRIDGE_GOOGLE_COMMAND__",
                            command
                                .toString()
                                .replace("\u2028", "\\u2028")
                                .replace("\u2029", "\\u2029"),
                        )
                } else
                    app.assets
                        .open("google-devices.js")
                        .bufferedReader()
                        .use { it.readText() }
                        .replace("__FINDBRIDGE_GOOGLE_INITIAL_INDEX__", requestedIndex.toString())
            WebViewCompat.addDocumentStartJavaScript(view, script, GoogleProtocol.dataOrigins)
            var loadFailed = false
            view.webViewClient =
                object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean {
                        val blocked =
                            request.isForMainFrame && !GoogleProtocol.isPage(request.url.toString())
                        if (blocked && BuildConfig.DEBUG) {
                            val uri = request.url
                            java.io
                                .File(app.filesDir, "google-web-blocked.json")
                                .writeText(
                                    JSONObject()
                                        .put("host", uri.host.orEmpty())
                                        .put(
                                            "path",
                                            uri.path
                                                .orEmpty()
                                                .replace(
                                                    Regex(
                                                        "[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}",
                                                        RegexOption.IGNORE_CASE,
                                                    ),
                                                    "<account>",
                                                )
                                                .take(200),
                                        )
                                        .toString()
                                )
                        }
                        if (blocked && BuildConfig.DEBUG)
                            onBlockedNavigation?.invoke(
                                request.url.host.orEmpty(),
                                request.url.path.orEmpty(),
                            )
                        if (blocked) onError?.invoke()
                        return blocked
                    }

                    override fun onPageStarted(
                        view: WebView,
                        url: String,
                        favicon: android.graphics.Bitmap?,
                    ) {
                        loadFailed = false
                        commandPage++
                        commandReady = false
                        commandGrantPending = false
                        if (command != null && commandGranted) {
                            view.stopLoading()
                            onCommandResult?.invoke("UNKNOWN")
                            return
                        }
                        debugPage(view, "started")
                        handler.removeCallbacks(timeout)
                        if (!GoogleProtocol.isPage(url)) {
                            loadFailed = true
                            view.stopLoading()
                            onError?.invoke()
                            return
                        }
                        if (GoogleProtocol.isDataPage(url)) handler.postDelayed(timeout, 100_000)
                        onPage?.invoke(url)
                        if (!attached && !GoogleProtocol.isDataPage(url)) {
                            handler.removeCallbacks(timeout)
                            onError?.invoke()
                            handler.post { destroyWeb() }
                        }
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        if (loadFailed) return
                        debugPage(view, "finished")
                        CookieManager.getInstance().flush()
                        onPage?.invoke(url)
                        if (GoogleProtocol.isDataPage(url))
                            view.evaluateJavascript("window.FindBridgeGoogleReader?.state()", null)
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError,
                    ) {
                        if (request.isForMainFrame) {
                            loadFailed = true
                            onError?.invoke()
                        }
                    }

                    override fun onRenderProcessGone(
                        view: WebView,
                        detail: android.webkit.RenderProcessGoneDetail,
                    ): Boolean {
                        onError?.invoke()
                        handler.post { destroyWeb() }
                        return true
                    }
                }
        }
    }

    fun attach(context: Context, index: Int = -1, operation: JSONObject? = null): WebView {
        destroyWeb()
        command = operation
        commandGranted = false
        commandReady = false
        commandCommitReady = false
        commandCommitGranted = false
        commandGrantPending = false
        attached = true
        requestedIndex = index.takeIf { it in 0..499 } ?: -1
        val view = create()
        wrapper?.baseContext = context
        (view.parent as? ViewGroup)?.removeView(view)
        view.onResume()
        // Retrying a failed navigation reloads the entry point while preserving
        // the owner's private cookies and completed verification.
        view.loadUrl(GoogleProtocol.FIND_URL)
        return view
    }

    fun detach() {
        attached = false
        web?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.onPause()
        }
        wrapper?.baseContext = app
        onPage = null
        onVerification = null
        onBlockedNavigation = null
        // A completed native handoff does not leave a second, hidden map rendering.
        destroyWeb()
        command = null
        commandGranted = false
        onCommandResult = null
        commandForeground = null
        refreshAfterDetach?.let { index ->
            refreshAfterDetach = null
            refresh(index)
        }
    }

    fun refresh(index: Int) {
        // The parent can resume and bind before the finishing auth Activity is
        // destroyed. Start its refresh after that Activity releases its WebView.
        if (attached) {
            refreshAfterDetach = index
            return
        }
        requestedIndex = index
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, 100_000)
        val view = create()
        view.onResume()
        if (GoogleProtocol.isDataPage(view.url.orEmpty()))
            view.evaluateJavascript("window.FindBridgeGoogleReader?.start($index)", null)
        else view.loadUrl(GoogleProtocol.FIND_URL)
    }

    fun stop() {
        refreshAfterDetach = null
        handler.removeCallbacks(timeout)
        if (!attached) destroyWeb()
    }

    fun cancelCommand() {
        onCommandResult?.invoke(if (commandGranted) "UNKNOWN" else "CANCELED")
    }

    fun resumeCommand() {
        val operation = command ?: return
        val view = web ?: return
        val committing = commandGranted && commandCommitReady && !commandCommitGranted
        if (
            !commandReady ||
                (commandGranted && !committing) ||
                commandGrantPending ||
                !attached ||
                commandForeground?.invoke() != true
        )
            return
        commandGrantPending = true
        val page = commandPage
        val ticket = operation.getString("ticket")
        commandWorker.execute {
            val authorized = runCatching {
                app.contentResolver
                    .call(
                        android.net.Uri.parse(
                            "content://${BuildConfig.APPLICATION_ID}.googleidentity"
                        ),
                        "authorizeCommand",
                        ticket,
                        null,
                    )
                    ?.getBoolean("authorized") == true
            }
                .getOrDefault(false)
            handler.post {
                if (web !== view || command !== operation || commandPage != page) return@post
                commandGrantPending = false
                if (!attached || commandForeground?.invoke() != true) return@post
                if (!authorized) onCommandResult?.invoke("CANCELED")
                else {
                    if (committing) commandCommitGranted = true else commandGranted = true
                    val method = if (committing) "commit" else "execute"
                    view.evaluateJavascript(
                        "window.FindBridgeGoogleAction?.$method(${JSONObject.quote(ticket)})",
                        null,
                    )
                }
            }
        }
    }

    fun clear(done: () -> Unit) {
        snapshot = null
        create().clearCache(true)
        destroyWeb()
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            done()
        }
    }

    private fun destroyWeb() {
        handler.removeCallbacks(timeout)
        web?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            view.stopLoading()
            view.destroy()
        }
        web = null
        wrapper = null
    }

    companion object {
        private var profileInitialized = false
        @android.annotation.SuppressLint("StaticFieldLeak") // Activity wrapper is reset on detach.
        private var instance: GoogleSession? = null

        fun get(context: Context) = instance ?: GoogleSession(context).also { instance = it }
    }
}
