package dev.findbridge.android.update

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import dev.findbridge.android.*
import dev.findbridge.android.MaterialControls.linearIndicator
import dev.findbridge.android.MaterialControls.materialSwitch
import dev.findbridge.android.Ui.actionButton
import dev.findbridge.android.Ui.column
import dev.findbridge.android.Ui.dp
import dev.findbridge.android.Ui.label
import dev.findbridge.android.Ui.space

/** Native update controls; the platform owns permission approval and package installation. */
class UpdateActivity : Activity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    private lateinit var updates: UpdateRepository
    private lateinit var content: LinearLayout
    private var resumed = false
    private var awaitingPermission = false
    private var confirmation: Intent? = null
    private lateinit var selection: DisplayChoices
    private lateinit var glass: GlassScene
    private var pendingRender = false
    private var posture: PostureObserver? = null
    private var fold: FoldRegion? = null
    private var layoutPane: () -> Unit = {}
    private val changed: () -> Unit = {
        if (::selection.isInitialized && selection.isShowing) pendingRender = true else render()
        showConfirmation()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Ui.configure(this)
        if (Ui.dark) setTheme(R.style.Theme_FindBridge_Dark)
        super.onCreate(savedInstanceState)
        updates = UpdateRepository.get(this)
        awaitingPermission = savedInstanceState?.getBoolean("installPermission") == true
        @Suppress("DEPRECATION")
        confirmation = intent.getParcelableExtra(APPROVAL)
        intent.removeExtra(APPROVAL)
        val root = FrameLayout(this).apply { setBackgroundColor(Ui.background) }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        content = column().apply { setPadding(dp(24), dp(16), dp(24), dp(28)) }
        scroll.addView(content)
        glass = GlassScene(root)
        selection =
            DisplayChoices(
                this,
                glass,
                { android.graphics.Rect().also(scroll::getGlobalVisibleRect) },
            ) {
                content.post {
                    if (!isDestroyed && pendingRender && !selection.isShowing) render()
                }
            }
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            }
            layoutPane()
            insets
        }
        layoutPane = { PostureObserver.placeInPane(root, scroll, fold) }
        root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) selection.dismiss()
            layoutPane()
        }
        setContentView(root)
        render()
    }

    private fun render() {
        if (!::content.isInitialized) return
        pendingRender = false
        content.removeAllViews()
        content.addView(
            label(getString(R.string.update_back), 16, Ui.blue).apply {
                minimumHeight = dp(48)
                gravity = Gravity.CENTER_VERTICAL
                setOnClickListener { finish() }
            }
        )
        space(content, 8)
        content.addView(label(getString(R.string.updates_title), 27, Ui.ink, true))
        content.addView(
            label(
                getString(R.string.update_installed_version, BuildConfig.VERSION_NAME),
                14,
                Ui.muted,
            )
        )
        space(content, 20)
        val state = updates.state
        val status =
            when (state.phase) {
                UpdatePhase.IDLE -> R.string.update_check_help
                UpdatePhase.CHECKING -> R.string.update_checking
                UpdatePhase.CURRENT -> R.string.update_current
                UpdatePhase.AVAILABLE -> R.string.update_available
                UpdatePhase.DOWNLOADING -> R.string.update_downloading
                UpdatePhase.VERIFYING -> R.string.update_verifying
                UpdatePhase.READY -> R.string.update_ready
                UpdatePhase.INSTALLING -> R.string.update_installing
                UpdatePhase.APPROVAL -> R.string.update_approval
                UpdatePhase.FAILED -> R.string.update_failed
            }
        val card =
            column().apply {
                setPadding(dp(18), dp(18), dp(18), dp(18))
                background = Ui.rounded(Ui.surface, dp(20))
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
        card.addView(label(getString(status), 18, Ui.ink, true).apply { id = STATUS_ID })
        state.release?.let { card.addView(label("Find Bridge ${it.version}", 15, Ui.muted)) }
        val busy =
            state.phase in
                setOf(
                    UpdatePhase.CHECKING,
                    UpdatePhase.DOWNLOADING,
                    UpdatePhase.VERIFYING,
                    UpdatePhase.INSTALLING,
                )
        if (busy)
            card.addView(
                linearIndicator().apply {
                    isIndeterminate = true
                },
                LinearLayout.LayoutParams(-1, dp(4)).apply { topMargin = dp(16) },
            )
        if (
            state.release != null &&
                state.phase !in setOf(UpdatePhase.IDLE, UpdatePhase.CURRENT, UpdatePhase.CHECKING)
        ) {
            space(card, 14)
            when (state.phase) {
                UpdatePhase.READY,
                UpdatePhase.APPROVAL ->
                    card.addView(
                        actionButton(getString(R.string.update_install)).apply {
                            id = INSTALL_ID
                            setOnClickListener { requestInstall() }
                        },
                        LinearLayout.LayoutParams(-1, -2),
                    )
                UpdatePhase.AVAILABLE,
                UpdatePhase.FAILED ->
                    card.addView(
                        actionButton(getString(R.string.update_download)).apply {
                            id = DOWNLOAD_ID
                            setOnClickListener { updates.download() }
                        },
                        LinearLayout.LayoutParams(-1, -2),
                    )
                else -> Unit
            }
            card.addView(
                actionButton(getString(R.string.cancel), false).apply {
                    setOnClickListener { updates.cancel() }
                },
                LinearLayout.LayoutParams(-1, -2),
            )
            if (state.release.notes.isNotBlank()) {
                space(card, 12)
                card.addView(label(state.release.notes, 14, Ui.muted))
            }
        }
        content.addView(card, LinearLayout.LayoutParams(-1, -2))
        space(content, 14)
        content.addView(
            actionButton(getString(R.string.update_check), false).apply {
                id = CHECK_ID
                isEnabled = !busy && state.phase !in setOf(UpdatePhase.READY, UpdatePhase.APPROVAL)
                setOnClickListener { updates.check(force = true) }
            },
            LinearLayout.LayoutParams(-1, -2),
        )
        space(content, 18)
        content.addView(
            materialSwitch().apply {
                id = AUTOMATIC_ID
                text = getString(R.string.update_automatic)
                isChecked = updates.automatic
                setOnCheckedChangeListener { _, value -> updates.setAutomatic(value) }
            },
            LinearLayout.LayoutParams(-1, -2),
        )
        content.addView(label(getString(R.string.update_automatic_help), 13, Ui.muted))
        space(content, 12)
        val intervals = listOf(0, 6, 12, 24)
        val intervalTitles =
            listOf(
                    R.string.update_frequency_startup,
                    R.string.update_frequency_6,
                    R.string.update_frequency_12,
                    R.string.update_frequency_24,
                )
                .map(::getString)
        content.addView(
            SettingsRow(
                    this,
                    getString(R.string.update_frequency),
                    intervalTitles[intervals.indexOf(updates.intervalHours)],
                ) {
                    selection.show(
                        it,
                        intervalTitles.mapIndexed { index, title ->
                            title to (intervals[index] == updates.intervalHours)
                        },
                    ) { index ->
                        updates.setInterval(intervals[index])
                        render()
                    }
                }
                .apply { id = FREQUENCY_ID }
        )
        space(content, 10)
        val channels =
            arrayOf(
                getString(R.string.update_channel_stable),
                getString(R.string.update_channel_preview),
            )
        content.addView(
            SettingsRow(
                    this,
                    getString(R.string.update_channel),
                    channels[if (updates.previews) 1 else 0],
                ) {
                    selection.show(
                        it,
                        channels.mapIndexed { index, title ->
                            title to (index == if (updates.previews) 1 else 0)
                        },
                    ) { index ->
                        updates.setPreviews(index == 1)
                        render()
                    }
                }
                .apply { id = PREVIEWS_ID }
        )
        if (!packageManager.canRequestPackageInstalls()) {
            space(content, 14)
            content.addView(
                actionButton(getString(R.string.update_allow_install), false).apply {
                    setOnClickListener { openInstallPermission() }
                },
                LinearLayout.LayoutParams(-1, -2),
            )
        }
        if (
            Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
        ) {
            content.addView(
                actionButton(getString(R.string.update_allow_notifications), false).apply {
                    setOnClickListener {
                        if (
                            updates.prefs.getBoolean("notificationRequested", false) &&
                                !shouldShowRequestPermissionRationale(
                                    Manifest.permission.POST_NOTIFICATIONS
                                )
                        ) {
                            startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                            )
                        } else {
                            updates.prefs.edit().putBoolean("notificationRequested", true).apply()
                            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
                        }
                    }
                },
                LinearLayout.LayoutParams(-1, -2),
            )
        }
    }

    private fun requestInstall() {
        if (!packageManager.canRequestPackageInstalls()) {
            awaitingPermission = true
            openInstallPermission()
        } else if (confirmation != null || updates.approval != null) showConfirmation()
        else updates.install()
    }

    private fun openInstallPermission() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName"),
                )
            )
        }
    }

    private fun showConfirmation() {
        if (!resumed || !hasWindowFocus()) return
        val cached = updates.takeApproval()
        val pending = confirmation ?: cached ?: return
        confirmation = null
        runCatching { startActivity(pending) }.onFailure { updates.installFailed() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        @Suppress("DEPRECATION") val pending = intent.getParcelableExtra<Intent>(APPROVAL)
        if (pending != null) confirmation = pending
        showConfirmation()
    }

    override fun onStart() {
        super.onStart()
        updates.addListener(changed)
        posture =
            PostureObserver(this) {
                    selection.dismiss()
                    fold = it
                    layoutPane()
                }
                .also { it.start() }
    }

    override fun onResume() {
        super.onResume()
        glass.setActive(true)
        resumed = true
        updates.resume()
        render()
        if (awaitingPermission) {
            awaitingPermission = false
            if (packageManager.canRequestPackageInstalls()) updates.install()
        }
        showConfirmation()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::updates.isInitialized) showConfirmation()
    }

    override fun onPause() {
        resumed = false
        glass.setActive(false)
        super.onPause()
    }

    override fun onStop() {
        selection.dismiss()
        updates.removeListener(changed)
        posture?.stop()
        posture = null
        UpdateScheduler.whenIdle(this)
        super.onStop()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    override fun onDestroy() {
        selection.dismiss()
        glass.dispose()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("installPermission", awaitingPermission)
    }

    companion object {
        const val APPROVAL = "installerApproval"
        const val CHECK_ID = 0x16fb0210
        const val AUTOMATIC_ID = 0x16fb0211
        const val PREVIEWS_ID = 0x16fb0212
        const val INSTALL_ID = 0x16fb0213
        const val DOWNLOAD_ID = 0x16fb0214
        const val STATUS_ID = 0x16fb0215
        const val FREQUENCY_ID = 0x16fb0216
    }
}
