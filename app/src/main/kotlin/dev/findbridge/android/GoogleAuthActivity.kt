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

/** Google's official sign-in is the only visible web content; devices open in native views. */
class GoogleAuthActivity : Activity() {
    override fun attachBaseContext(newBase: android.content.Context) =
        super.attachBaseContext(AppLanguage.wrap(newBase))

    private lateinit var session: GoogleSession
    private var dataCallback: ((String) -> Unit)? = null
    private var errorCallback: (() -> Unit)? = null
    private var posture: PostureObserver? = null
    private var fold: FoldRegion? = null
    private var layoutPane: () -> Unit = {}

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
        val web = session.attach(this)
        frame.addView(web, FrameLayout.LayoutParams(-1, -1))
        val loading =
            column().apply {
                gravity = Gravity.CENTER
                setPadding(dp(28), dp(24), dp(28), dp(24))
                setBackgroundColor(Ui.surface)
                isClickable = true
                isFocusable = true
                addView(ProgressBar(this@GoogleAuthActivity))
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
            loading.visibility =
                if (android.net.Uri.parse(url).host == "www.google.com") View.VISIBLE else View.GONE
            if (url.contains("/android/find/about"))
                web.evaluateJavascript(
                    "(()=>{const a=[...document.querySelectorAll('a[href]')].find(e=>{try{return new URL(e.href).hostname==='accounts.google.com'}catch(_){return false}});if(a){const u=new URL(a.href);u.searchParams.set('continue','https://www.google.com/android/find/');u.searchParams.set('followup','https://www.google.com/android/find/');location.assign(u.href);}})()",
                    null,
                )
        }
        session.onPage = ::page
        session.onVerification = { required ->
            loading.visibility = if (required) View.GONE else View.VISIBLE
        }
        dataCallback = { snapshot ->
            if (org.json.JSONObject(snapshot).optBoolean("complete")) {
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
                    setOnClickListener { web.loadUrl(GoogleProtocol.FIND_URL) }
                }
            )
        }
        session.onError = errorCallback
        page(web.url.orEmpty())
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
        super.onDestroy()
    }

    companion object {
        const val SNAPSHOT = "googleDevices"
    }
}
