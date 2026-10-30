package dev.findbridge.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import dev.findbridge.android.Ui.actionButton
import dev.findbridge.android.Ui.column
import dev.findbridge.android.Ui.dp
import dev.findbridge.android.Ui.label
import dev.findbridge.android.Ui.space
import java.util.concurrent.Executors

/**
 * Native credential entry, with explicit local storage controls and no saved plaintext UI state.
 */
class LoginActivity : Activity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    private lateinit var vault: LoginVault
    private lateinit var account: EditText
    private lateinit var password: EditText
    private lateinit var remember: Switch
    private lateinit var automatic: Switch
    private lateinit var submit: TextView
    private var existing: SavedLogin? = null
    private val worker = Executors.newSingleThreadExecutor()
    private var busy = false
    private var credentialGeneration = 0
    private var completed = false
    private var posture: PostureObserver? = null
    private var fold: FoldRegion? = null
    private var layoutPane: () -> Unit = {}

    override fun onCreate(savedInstanceState: Bundle?) {
        Ui.configure(this)
        if (Ui.dark) setTheme(R.style.Theme_FindBridge_Dark)
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        vault = LoginVault(this)
        val root = FrameLayout(this).apply { setBackgroundColor(Ui.background) }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = column().apply { setPadding(dp(26), dp(24), dp(26), dp(24)) }
        content.addView(
            label(getString(R.string.cancel), 15, Ui.blue).apply {
                minimumHeight = dp(44)
                gravity = Gravity.CENTER_VERTICAL
                setOnClickListener { finish() }
            }
        )
        space(content, 14)
        content.addView(label(getString(R.string.connect_account), 27, Ui.ink, true))
        space(content, 20)
        fun field(hintId: Int, type: Int, viewId: Int) =
            EditText(this).apply {
                id = viewId
                hint = getString(hintId)
                inputType = type
                textSize = 17f
                setTextColor(Ui.ink)
                setHintTextColor(Ui.muted)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = Ui.rounded(Ui.surface, dp(14))
                isSingleLine = true
                isSaveEnabled = false
            }
        account =
            field(
                    R.string.email,
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                    ACCOUNT_ID,
                )
                .apply {
                    setAutofillHints(View.AUTOFILL_HINT_USERNAME)
                }
        password =
            field(
                    R.string.password,
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                    PASSWORD_ID,
                )
                .apply {
                    setAutofillHints(View.AUTOFILL_HINT_PASSWORD)
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    transformationMethod =
                        android.text.method.PasswordTransformationMethod.getInstance()
                }
        content.addView(account, LinearLayout.LayoutParams(-1, dp(58)))
        space(content, 10)
        content.addView(password, LinearLayout.LayoutParams(-1, dp(58)))
        space(content, 14)
        remember =
            Switch(this).apply {
                text = getString(R.string.save_login)
                textSize = 14f
                setTextColor(Ui.ink)
                isChecked = true
            }
        automatic =
            Switch(this).apply {
                text = getString(R.string.auto_login)
                textSize = 14f
                setTextColor(Ui.ink)
                isChecked = true
            }
        remember.setOnCheckedChangeListener { _, checked ->
            automatic.isEnabled = checked
            if (!checked) automatic.isChecked = false
        }
        content.addView(remember, LinearLayout.LayoutParams(-1, dp(52)))
        content.addView(automatic, LinearLayout.LayoutParams(-1, dp(48)))
        space(content, 14)
        content.addView(label(getString(R.string.secure_login_help), 13, Ui.muted))
        space(content, 10)
        content.addView(label(getString(R.string.verification_help), 12, Ui.muted))
        space(content, 22)
        submit =
            actionButton(getString(R.string.connect_account)).apply {
                id = CONTINUE_ID
                setOnClickListener { connect() }
            }
        content.addView(submit, LinearLayout.LayoutParams(-1, dp(52)))
        if (vault.hasSaved) {
            space(content, 12)
            content.addView(
                actionButton(getString(R.string.forget_login), false).apply {
                    setOnClickListener {
                        if (busy) return@setOnClickListener
                        credentialGeneration++
                        existing = null
                        account.text.clear()
                        password.text.clear()
                        password.hint = getString(R.string.password)
                        vault.pause()
                        worker.execute {
                            vault.clear()
                            runOnUiThread {
                                if (isFinishing || isDestroyed) return@runOnUiThread
                                Toast.makeText(
                                        this@LoginActivity,
                                        R.string.login_deleted,
                                        Toast.LENGTH_SHORT,
                                    )
                                    .show()
                            }
                        }
                    }
                },
                LinearLayout.LayoutParams(-1, dp(52)),
            )
        }
        val center = FrameLayout(this)
        center.addView(content, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        scroll.addView(center)
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        layoutPane = { PostureObserver.placeInPane(root, scroll, fold) }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutPane() }
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val bars =
                    insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            }
            layoutPane()
            insets
        }
        center.addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->
            val width = minOf(r - l - dp(24), dp(460)).coerceAtLeast(dp(200))
            if (content.layoutParams.width != width)
                content.layoutParams = content.layoutParams.apply { this.width = width }
        }
        setContentView(root)
        val loadGeneration = credentialGeneration
        worker.execute {
            val saved = vault.load()
            runOnUiThread {
                if (
                    isFinishing ||
                        isDestroyed ||
                        loadGeneration != credentialGeneration ||
                        account.text.isNotEmpty() ||
                        password.text.isNotEmpty()
                )
                    return@runOnUiThread
                existing = saved
                saved?.let {
                    account.setText(it.account)
                    password.hint = getString(R.string.saved_password_hint)
                    automatic.isChecked = vault.automatic
                }
            }
        }
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

    private fun connect() {
        if (busy) return
        val email = account.text.toString().trim()
        val secret =
            password.text.toString().ifEmpty {
                existing?.takeIf { it.account == email }?.password.orEmpty()
            }
        if (email.isEmpty() || secret.isEmpty()) {
            Toast.makeText(this, R.string.enter_login, Toast.LENGTH_SHORT).show()
            return
        }
        busy = true
        getSystemService(android.view.autofill.AutofillManager::class.java)?.cancel()
        getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            .hideSoftInputFromWindow(account.windowToken, 0)
        credentialGeneration++
        val requestGeneration = credentialGeneration
        submit.isEnabled = false
        submit.text = getString(R.string.connecting)
        val login = SavedLogin(email, secret)
        val keep = remember.isChecked
        val auto = automatic.isChecked
        worker.execute {
            runCatching {
                if (keep) vault.save(login, auto) else vault.clear()
                LoginVault.stage(login)
            }
                .onSuccess {
                    runOnUiThread {
                        if (
                            isFinishing || isDestroyed || requestGeneration != credentialGeneration
                        ) {
                            vault.pause()
                            LoginVault.clearStaged()
                            return@runOnUiThread
                        }
                        password.text.clear()
                        existing = null
                        AppleRepository.get(this).logout()
                        @Suppress("DEPRECATION")
                        startActivityForResult(
                            Intent(this, AuthActivity::class.java)
                                .putExtra(AuthActivity.USE_LOGIN, true),
                            10,
                        )
                    }
                }
                .onFailure {
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        busy = false
                        submit.isEnabled = true
                        submit.text = getString(R.string.connect_account)
                        Toast.makeText(this, R.string.save_login_failed, Toast.LENGTH_LONG).show()
                    }
                }
        }
    }

    @Deprecated("Activity result compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 10) {
            if (resultCode == RESULT_OK) {
                completed = true
                setResult(RESULT_OK)
                finish()
            } else {
                busy = false
                submit.isEnabled = false
                submit.text = getString(R.string.connect_account)
                val generation = credentialGeneration
                worker.execute {
                    val saved = vault.load()
                    runOnUiThread {
                        if (isFinishing || isDestroyed || generation != credentialGeneration)
                            return@runOnUiThread
                        existing = saved?.takeIf { it.account == account.text.toString().trim() }
                        password.hint =
                            getString(
                                if (existing != null) R.string.saved_password_hint
                                else R.string.password
                            )
                        submit.isEnabled = true
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        existing = null
        if (isFinishing && busy && !completed) {
            vault.pause()
            LoginVault.clearStaged()
        }
        worker.shutdown()
        super.onDestroy()
    }

    companion object {
        const val ACCOUNT_ID = 1101
        const val PASSWORD_ID = 1102
        const val CONTINUE_ID = 1103
    }
}
