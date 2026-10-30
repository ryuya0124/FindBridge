package dev.findbridge.android

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.AccountPicker
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import dev.findbridge.android.Ui.actionButton
import dev.findbridge.android.Ui.column
import dev.findbridge.android.Ui.dp
import dev.findbridge.android.Ui.label

/** Native GMS sign-in using public identity scopes. Find Hub access is a separate capability. */
class GoogleAuthActivity : Activity() {
    override fun attachBaseContext(newBase: android.content.Context) =
        super.attachBaseContext(AppLanguage.wrap(newBase))

    private lateinit var body: LinearLayout
    private var account: Account? = null
    private var busy = false
    private var resumed = false
    private var requestGeneration = 0
    private var pendingConsent: android.app.PendingIntent? = null
    private var externalRequest = 0
    private var pendingWeb = false
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var posture: PostureObserver? = null
    private var fold: FoldRegion? = null
    private var layoutPane: () -> Unit = {}

    override fun onCreate(savedInstanceState: Bundle?) {
        Ui.configure(this)
        if (Ui.dark) setTheme(R.style.Theme_FindBridge_Dark)
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        val root = column().apply { setBackgroundColor(Ui.surface) }
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
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
        header.addView(label(getString(R.string.google_connect), 16, Ui.ink, true))
        root.addView(header)
        body =
            column().apply {
                gravity = Gravity.CENTER
                setPadding(dp(28), dp(24), dp(28), dp(24))
            }
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        val surface = FrameLayout(this).apply { setBackgroundColor(Ui.surface) }
        surface.addView(root, FrameLayout.LayoutParams(-1, -1))
        layoutPane = { PostureObserver.placeInPane(surface, root, fold) }
        surface.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutPane() }
        setContentView(surface)
        // Restored activities do not relaunch a permission dialog that may still be open.
        externalRequest = savedInstanceState?.getInt("external", 0) ?: 0
        busy = externalRequest != 0
        if (busy) loading() else showMessage(R.string.google_gms_help)
    }

    private fun loading() {
        body.removeAllViews()
        body.addView(ProgressBar(this))
        body.addView(
            label(getString(R.string.google_gms_connecting), 18, Ui.ink).apply {
                id = STATUS_ID
                gravity = Gravity.CENTER
                setPadding(0, dp(24), 0, dp(16))
            }
        )
    }

    private fun showMessage(message: Int) {
        busy = false
        externalRequest = 0
        pendingConsent = null
        account = null
        body.removeAllViews()
        body.addView(
            label(getString(message), 17, Ui.ink).apply {
                id = STATUS_ID
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(24))
            }
        )
        body.addView(
            actionButton(getString(R.string.google_choose_account)).apply {
                id = CONNECT_ID
                setOnClickListener { chooseAccount() }
            },
            LinearLayout.LayoutParams(-1, dp(52)),
        )
    }

    private fun chooseAccount() {
        if (busy) return
        if (
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this) !=
                ConnectionResult.SUCCESS
        ) {
            showMessage(R.string.google_gms_unavailable)
            return
        }
        busy = true
        requestGeneration++
        loading()
        try {
            @Suppress("DEPRECATION")
            externalRequest = PICK_ACCOUNT
            startActivityForResult(
                AccountPicker.newChooseAccountIntent(
                    AccountPicker.AccountChooserOptions.Builder()
                        .setAllowableAccountsTypes(listOf("com.google"))
                        .setAlwaysShowAccountPicker(true)
                        .build()
                ),
                PICK_ACCOUNT,
            )
        } catch (_: Exception) {
            showMessage(R.string.google_gms_unavailable)
        }
    }

    private fun authorize() {
        loading()
        val generation = requestGeneration
        val request =
            AuthorizationRequest.builder()
                .setRequestedScopes(listOf(Scope("openid"), Scope(EMAIL_SCOPE)))
        account?.let { request.setAccount(it) }
        Identity.getAuthorizationClient(this)
            .authorize(request.build())
            .addOnSuccessListener { result ->
                if (isDestroyed || isFinishing || generation != requestGeneration)
                    return@addOnSuccessListener
                if (result.hasResolution()) {
                    pendingConsent = result.pendingIntent
                    launchConsent()
                } else authorized(result)
            }
            .addOnFailureListener {
                if (!isDestroyed && !isFinishing && generation == requestGeneration) failed(it)
            }
    }

    private fun launchConsent() {
        if (!resumed || !hasWindowFocus()) return
        val pending = pendingConsent
        if (pending == null) {
            if (pendingWeb) {
                pendingWeb = false
                externalRequest = WEB_SESSION
                @Suppress("DEPRECATION")
                startActivityForResult(
                    Intent(this, GoogleWebSessionActivity::class.java),
                    WEB_SESSION,
                )
            }
            return
        }
        pendingConsent = null
        try {
            externalRequest = AUTHORIZE
            @Suppress("DEPRECATION")
            startIntentSenderForResult(pending.intentSender, AUTHORIZE, null, 0, 0, 0)
        } catch (_: Exception) {
            showMessage(R.string.google_gms_failed)
        }
    }

    private fun authorized(result: AuthorizationResult) {
        val token = result.accessToken
        if (token.isNullOrBlank()) {
            showMessage(R.string.google_gms_failed)
            return
        }
        val expectedEmail = account?.name
        val generation = requestGeneration
        loading()
        worker.execute {
            val identity = runCatching { GoogleIdentityClient.fetch(token, expectedEmail) }
            runOnUiThread {
                if (isFinishing || isDestroyed || requestGeneration != generation)
                    return@runOnUiThread
                identity
                    .onSuccess { verified ->
                        GoogleRepository.get(this).acceptIdentity(verified) { stored ->
                            if (isFinishing || isDestroyed || requestGeneration != generation)
                                return@acceptIdentity
                            if (stored) {
                                pendingWeb = true
                                launchConsent()
                            } else showMessage(R.string.google_gms_failed)
                        }
                    }
                    .onFailure { showMessage(R.string.google_gms_failed) }
            }
        }
    }

    private fun failed(failure: Exception) {
        val error = GoogleGmsError.classify((failure as? ApiException)?.statusCode, failure.message)
        showMessage(
            when (error) {
                GoogleGmsError.CONFIGURATION -> R.string.google_gms_configuration
                GoogleGmsError.SCOPE -> R.string.google_gms_scope_unavailable
                GoogleGmsError.CANCELED -> R.string.google_gms_help
                GoogleGmsError.OTHER -> R.string.google_gms_failed
            }
        )
    }

    @Deprecated("Activity result compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_ACCOUNT || requestCode == AUTHORIZE || requestCode == WEB_SESSION)
            externalRequest = 0
        if (requestCode == WEB_SESSION) {
            setResult(resultCode, data)
            finish()
            return
        }
        if (requestCode == PICK_ACCOUNT) {
            val name = data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
            val type = data?.getStringExtra(AccountManager.KEY_ACCOUNT_TYPE)
            if (resultCode != RESULT_OK || name.isNullOrBlank() || type != "com.google") {
                showMessage(R.string.google_gms_help)
                return
            }
            account = Account(name, type)
            authorize()
        } else if (requestCode == AUTHORIZE) {
            // A canceled Activity can still contain a meaningful GMS error (e.g. unregistered).
            try {
                authorized(
                    Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(data)
                )
            } catch (failure: Exception) {
                failed(failure)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("external", externalRequest)
        super.onSaveInstanceState(outState)
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

    override fun onResume() {
        super.onResume()
        resumed = true
        launchConsent()
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) launchConsent()
    }

    override fun onStop() {
        posture?.stop()
        posture = null
        super.onStop()
    }

    override fun onDestroy() {
        requestGeneration++
        worker.shutdown()
        account = null
        pendingConsent = null
        super.onDestroy()
    }

    companion object {
        const val SNAPSHOT = "googleDevices"
        const val STATUS_ID = 1310
        const val CONNECT_ID = 1311
        const val EMAIL_SCOPE = "https://www.googleapis.com/auth/userinfo.email"
        private const val PICK_ACCOUNT = 31
        private const val AUTHORIZE = 32
        private const val WEB_SESSION = 33
    }
}
