@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package dev.findbridge.android

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import dev.findbridge.android.Ui.actionButton
import dev.findbridge.android.Ui.column
import dev.findbridge.android.Ui.dp
import dev.findbridge.android.Ui.label
import dev.findbridge.android.Ui.space
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/** Every primary screen is drawn with native Android views and MapLibre's native renderer. */
class MainActivity : Activity(), AppleRepository.Listener {
    private lateinit var repository: AppleRepository
    private lateinit var locationSync: LocationSyncRepository
    private lateinit var appearance: AppearanceSettings
    private lateinit var glass: GlassScene
    private lateinit var root: FrameLayout
    private lateinit var mapView: MapView
    private var map: MapLibreMap? = null
    private lateinit var sheet: LinearLayout
    private lateinit var body: LinearLayout
    private lateinit var tabs: LinearLayout
    private lateinit var controls: LinearLayout
    private lateinit var attribution: TextView
    private lateinit var statusPill: TextView
    private val markers = mutableMapOf<Marker, String>()
    private var selectedId: String? = null
    private var accountTab = false
    private var activeTab = 1
    private var fitted = false
    private var topInset = 0
    private var bottomInset = 0
    private var sheetHeight = 0
    private var dragY = 0f
    private var dragHeight = 0
    private val handler = Handler(Looper.getMainLooper())
    private val periodicRefresh =
        object : Runnable {
            override fun run() {
                repository.refresh()
                locationSync.refresh()
                handler.postDelayed(this, 30_000)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        Ui.configure(this)
        if (Ui.dark) setTheme(R.style.Theme_FindBridge_Dark)
        super.onCreate(savedInstanceState)
        appearance = AppearanceSettings(this)
        MapLibre.getInstance(this)
        repository = AppleRepository.get(this)
        locationSync = LocationSyncRepository.get(this)
        root =
            FrameLayout(this).apply {
                setBackgroundColor(Ui.background)
                systemUiVisibility =
                    if (Ui.dark) 0
                    else
                        View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                            View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
        if (android.os.Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        mapView =
            MapView(this).apply {
                id = MAP_ID
                onCreate(savedInstanceState)
            }
        root.addView(mapView, FrameLayout.LayoutParams(-1, -1))
        glass = GlassScene(mapView).apply { intensity = appearance.glassIntensity }
        mapView.getMapAsync { value ->
            map = value
            value.uiSettings.apply {
                isLogoEnabled = false
                isAttributionEnabled = false
                isCompassEnabled = false
            }
            value.setMinZoomPreference(2.0)
            value.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(36.2, 138.2), 4.5))
            applyMapStyle()
            value.addOnCameraMoveListener { glass.capture(value) }
            value.addOnCameraIdleListener { glass.capture(value, immediate = true) }
            mapView.addOnDidBecomeIdleListener { glass.capture(value, immediate = true) }
            value.setOnMarkerClickListener { marker ->
                markers[marker]?.let(::select)
                true
            }
        }
        createMapControls()
        createSheet()
        createTabs()
        root.setOnApplyWindowInsetsListener { _, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val bars =
                    insets.getInsets(
                        WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                    )
                topInset = bars.top
                bottomInset = bars.bottom
            } else {
                topInset = insets.systemWindowInsetTop
                bottomInset = insets.systemWindowInsetBottom
            }
            layoutOverlays()
            insets
        }
        setContentView(root)
        selectedId = savedInstanceState?.getString("selected")
        activeTab = savedInstanceState?.getInt("tab", 1) ?: 1
        accountTab = activeTab == 3
        updateTabs()
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                ::back,
            )
        }
        repository.addListener(this)
        locationSync.addListener(this)
        renderSheet()
    }

    private fun roundButton(glyph: String, description: String) =
        Button(this).apply {
            text = glyph
            textSize = 23f
            isAllCaps = false
            setTextColor(Ui.ink)
            setPadding(0, 0, 0, 0)
            minWidth = 0
            minimumWidth = 0
            backgroundTintList = null
            background = GlassDrawable(glass, this, dp(22).toFloat(), .18f)
            stateListAnimator = null
            elevation = dp(5).toFloat()
            contentDescription = description
        }

    private fun createMapControls() {
        controls = column()
        controls.addView(
            roundButton("▱", "地図の表示設定").apply {
                setOnClickListener { showMapSettings() }
            },
            LinearLayout.LayoutParams(dp(48), dp(48)),
        )
        space(controls, 10)
        controls.addView(
            roundButton("⌖", "すべてのデバイスを地図に表示").apply {
                setOnClickListener { fitDevices(force = true) }
            },
            LinearLayout.LayoutParams(dp(48), dp(48)),
        )
        root.addView(controls, FrameLayout.LayoutParams(dp(48), -2, Gravity.TOP or Gravity.END))
        statusPill =
            label("探す", 14, Ui.ink, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(15), dp(9), dp(15), dp(9))
                background = GlassDrawable(glass, this, dp(22).toFloat(), .22f)
                elevation = dp(2).toFloat()
            }
        root.addView(statusPill, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
        attribution =
            label("© OpenFreeMap · OpenMapTiles · OpenStreetMap", 9, Ui.muted).apply {
                setPadding(dp(5), dp(2), dp(5), dp(2))
                background =
                    Ui.rounded(if (Ui.dark) 0xc51c1c1e.toInt() else 0xdaffffff.toInt(), dp(5))
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("地図の出典")
                        .setMessage(
                            "© OpenFreeMap · openfreemap.org\n© OpenMapTiles · openmaptiles.org\n© OpenStreetMap contributors · openstreetmap.org/copyright\n\n航空写真：国土地理院 · 地理院タイル\nhttps://maps.gsi.go.jp/development/ichiran.html\n画像：Landsat8（GSI, TSIC, GEO Grid/AIST / USGS）, 海底地形 GEBCO, GRUS画像 © Axelspace\n世界衛星モザイク画像：NASA LP DAAC / USGS EROS Center"
                        )
                        .setPositiveButton("閉じる", null)
                        .show()
                }
            }
        root.addView(attribution, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START))
    }

    private fun applyMapStyle() {
        val current = map ?: return
        fitted = false
        current.setStyle(
            Style.Builder().fromJson(MapStyles.create(this, appearance.mapMode, Ui.dark))
        ) {
            renderMarkers()
            if (selectedId != null) selected()?.let { select(it.id) } else fitDevices()
            glass.capture(current, immediate = true)
            if (::attribution.isInitialized)
                attribution.text =
                    when (appearance.mapMode) {
                        MapMode.STANDARD -> "© OpenFreeMap · OpenMapTiles · OpenStreetMap"
                        MapMode.AERIAL -> "出典：国土地理院 · 地理院タイル"
                        MapMode.HYBRID -> "地理院タイル · © OpenStreetMap"
                    }
        }
    }

    private fun showMapSettings() {
        val content = column().apply { setPadding(dp(22), dp(8), dp(22), dp(12)) }
        content.addView(label("地図の表示", 20, Ui.ink, true))
        space(content, 14)
        val modes = LinearLayout(this)
        val buttons = mutableListOf<Button>()
        MapMode.entries.forEach { mode ->
            val button =
                actionButton(mode.label, appearance.mapMode == mode).apply {
                    textSize = 11f
                    setSingleLine()
                    ellipsize = TextUtils.TruncateAt.END
                    setOnClickListener {
                        appearance.mapMode = mode
                        applyMapStyle()
                        buttons.forEachIndexed { index, item ->
                            val active = MapMode.entries[index] == mode
                            item.setTextColor(if (active) Color.WHITE else Ui.ink)
                            item.background =
                                Ui.rounded(if (active) Ui.green else Ui.surface, dp(16))
                        }
                    }
                }
            buttons += button
            modes.addView(
                button,
                LinearLayout.LayoutParams(0, dp(58), 1f).apply { rightMargin = dp(5) },
            )
        }
        content.addView(modes)
        space(content, 18)
        content.addView(label("外観", 16, Ui.ink, true))
        space(content, 6)
        val theme = actionButton(appearance.appearance.label, false)
        content.addView(theme, LinearLayout.LayoutParams(-1, dp(48)))
        theme.setOnClickListener { showAppearanceSettings() }
        space(content, 14)
        content.addView(label("ガラスの透明感", 16, Ui.ink, true))
        content.addView(
            SeekBar(this).apply {
                max = 100
                progress = (appearance.glassIntensity * 100).toInt()
                setOnSeekBarChangeListener(
                    object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(
                            seekBar: SeekBar?,
                            progress: Int,
                            fromUser: Boolean,
                        ) {
                            if (fromUser) {
                                appearance.glassIntensity = progress / 100f
                                glass.intensity = appearance.glassIntensity
                                glass.invalidate()
                            }
                        }

                        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                    }
                )
            },
            LinearLayout.LayoutParams(-1, dp(44)),
        )
        content.addView(label("航空写真は地理院タイルを使用します。国内は高解像度、国外は提供範囲に依存します。", 11, Ui.muted))
        AlertDialog.Builder(this).setView(content).setPositiveButton("完了", null).show()
    }

    private fun showAppearanceSettings() {
        AlertDialog.Builder(this)
            .setTitle("外観")
            .setSingleChoiceItems(
                Appearance.entries.map { it.label }.toTypedArray(),
                appearance.appearance.ordinal,
            ) { dialog, index ->
                appearance.appearance = Appearance.entries[index]
                dialog.dismiss()
                recreate()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createSheet() {
        sheetHeight = dp(390)
        sheet =
            column().apply {
                id = SHEET_ID
                background = GlassDrawable(glass, this, dp(32).toFloat(), .72f)
                elevation = dp(9).toFloat()
                clipToOutline = true
            }
        val handleArea =
            column().apply {
                gravity = Gravity.CENTER
                contentDescription = "デバイス一覧の高さを変更"
            }
        handleArea.addView(
            View(this).apply {
                background =
                    Ui.rounded(if (Ui.dark) 0xff636369.toInt() else 0xffc1c1c8.toInt(), dp(3))
            },
            LinearLayout.LayoutParams(dp(36), dp(5)),
        )
        handleArea.setOnClickListener {
            sheetHeight =
                if (sheetHeight < root.height / 2) root.height - topInset - dp(120) else dp(390)
            layoutOverlays()
        }
        handleArea.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragY = event.rawY
                    dragHeight = sheet.height
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    sheetHeight =
                        (dragHeight + (dragY - event.rawY).toInt()).coerceIn(
                            dp(145),
                            (root.height - topInset - dp(110)).coerceAtLeast(dp(145)),
                        )
                    layoutOverlays()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (kotlin.math.abs(dragY - event.rawY) < dp(4)) view.performClick()
                    true
                }
                else -> false
            }
        }
        sheet.addView(handleArea, LinearLayout.LayoutParams(-1, dp(25)))
        body = column()
        sheet.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(sheet, FrameLayout.LayoutParams(-1, sheetHeight, Gravity.BOTTOM))
    }

    private fun createTabs() {
        tabs =
            LinearLayout(this).apply {
                gravity = Gravity.CENTER
                background = GlassDrawable(glass, this, dp(36).toFloat(), .24f)
                elevation = dp(13).toFloat()
                clipToOutline = true
                setPadding(dp(5), dp(4), dp(5), dp(4))
            }
        addTab(0, "人を探す") { switchTab(0) }
        addTab(1, "デバイス") { switchTab(1) }
        addTab(2, "持ち物") { switchTab(2) }
        addTab(3, "自分") { switchTab(3) }
        root.addView(tabs, FrameLayout.LayoutParams(-1, dp(68), Gravity.BOTTOM))
        updateTabs()
    }

    private fun addTab(icon: Int, title: String, action: () -> Unit) {
        val tab =
            column().apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(5), 0, dp(7))
                contentDescription = title
                addView(
                    TabGlyph(this@MainActivity, icon),
                    LinearLayout.LayoutParams(dp(28), dp(31)),
                )
                addView(label(title, 10, Ui.muted).apply { gravity = Gravity.CENTER })
                setOnClickListener { action() }
            }
        tabs.addView(tab, LinearLayout.LayoutParams(0, dp(60), 1f))
    }

    private fun updateTabs() {
        for (index in 0 until tabs.childCount) {
            val selected = index == activeTab
            val tab = tabs.getChildAt(index) as LinearLayout
            val color = if (selected) Ui.green else Ui.muted
            tab.background =
                if (selected) Ui.rounded(if (Ui.dark) 0x3bffffff else 0x72ffffff, dp(30)) else null
            (tab.getChildAt(0) as TabGlyph).tint = color
            (tab.getChildAt(1) as TextView).setTextColor(color)
        }
    }

    private fun switchTab(index: Int) {
        activeTab = index
        accountTab = index == 3
        selectedId = null
        fitted = false
        renderSheet()
        updateTabs()
        renderMarkers()
        fitDevices(force = true)
    }

    private fun sharedEntries() =
        when (activeTab) {
            0 -> locationSync.people
            2 -> locationSync.items
            else -> emptyList()
        }

    private fun visibleDevices(): List<AppleDevice> =
        when (activeTab) {
            0,
            2 -> sharedEntries().map { it.asDevice() }
            1 -> repository.devices + locationSync.pencils.map { it.asDevice() }
            else -> emptyList()
        }

    private fun chooseLocationFile() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                )
            },
            20,
        )
    }

    private fun showSourceSettings() {
        val content = column().apply { setPadding(dp(24), dp(12), dp(24), dp(12)) }
        content.addView(label("人・持ち物の連携", 22, Ui.ink, true))
        space(content, 12)
        content.addView(
            label(
                "人・持ち物の情報は、デバイス一覧とは別にAppleが暗号化しています。連携元の接続ファイル、または所有者が書き出した位置データを読み込めます。",
                14,
                Ui.muted,
            )
        )
        space(content, 18)
        content.addView(
            actionButton("連携ファイルを選ぶ").apply { setOnClickListener { chooseLocationFile() } },
            LinearLayout.LayoutParams(-1, dp(50)),
        )
        if (locationSync.configured) {
            space(content, 10)
            content.addView(
                actionButton("連携を解除", false).apply {
                    setOnClickListener { locationSync.disconnect() }
                },
                LinearLayout.LayoutParams(-1, dp(48)),
            )
        }
        AlertDialog.Builder(this).setView(content).setPositiveButton("完了", null).show()
    }

    private fun layoutOverlays() {
        (controls.layoutParams as FrameLayout.LayoutParams).apply {
            topMargin = topInset + dp(14)
            rightMargin = dp(14)
            controls.layoutParams = this
        }
        (statusPill.layoutParams as FrameLayout.LayoutParams).apply {
            topMargin = topInset + dp(17)
            leftMargin = dp(16)
            statusPill.layoutParams = this
        }
        val tabHeight = dp(68) + bottomInset + dp(12)
        (tabs.layoutParams as FrameLayout.LayoutParams).apply {
            height = dp(68)
            bottomMargin = bottomInset + dp(8)
            leftMargin = dp(16)
            rightMargin = dp(16)
            tabs.layoutParams = this
        }
        (sheet.layoutParams as FrameLayout.LayoutParams).apply {
            height = sheetHeight
            bottomMargin = tabHeight + dp(8)
            leftMargin = dp(8)
            rightMargin = dp(8)
            sheet.layoutParams = this
        }
        (attribution.layoutParams as FrameLayout.LayoutParams).apply {
            bottomMargin = sheetHeight + tabHeight + dp(5)
            leftMargin = dp(6)
            attribution.layoutParams = this
        }
        if (selectedId != null) {
            map?.moveCamera(
                CameraUpdateFactory.paddingTo(
                    dp(25).toDouble(),
                    (topInset + dp(35)).toDouble(),
                    dp(25).toDouble(),
                    (sheetHeight + tabHeight + dp(30)).toDouble(),
                )
            )
        }
        glass.invalidate()
    }

    private fun renderSheet() {
        body.removeAllViews()
        if (accountTab) {
            renderAccount()
            return
        }
        selected()?.let {
            renderDetails(it)
            return
        }
        if (activeTab == 0 || activeTab == 2) {
            renderSharedList()
            return
        }
        val header =
            LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(22), dp(2), dp(14), dp(12))
            }
        header.addView(label("デバイス", 28, Ui.ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(
            roundButton(if (repository.loading) "···" else "↻", "デバイスの位置を更新").apply {
                id = REFRESH_ID
                elevation = 0f
                setTextColor(Ui.blue)
                isEnabled = !repository.loading
                setOnClickListener { repository.refresh() }
            },
            LinearLayout.LayoutParams(dp(44), dp(44)),
        )
        header.addView(
            roundButton("···", "アカウントメニュー").apply {
                elevation = 0f
                setTextColor(Ui.blue)
                setOnClickListener(::accountMenu)
            },
            LinearLayout.LayoutParams(dp(44), dp(44)),
        )
        body.addView(header)
        if (repository.error.isNotBlank())
            body.addView(
                label(repository.error, 12, 0xffd87520.toInt()).apply {
                    setPadding(dp(22), 0, dp(22), dp(8))
                    setOnClickListener {
                        if (repository.error.contains("サインイン")) connect() else repository.refresh()
                    }
                }
            )
        if (!repository.hasSession && locationSync.pencils.isEmpty()) {
            renderSignIn()
            return
        }
        if (visibleDevices().isEmpty()) {
            body.addView(
                label(if (repository.loading) "デバイスを読み込んでいます…" else "デバイスがありません", 16, Ui.muted)
                    .apply {
                        gravity = Gravity.CENTER
                    },
                LinearLayout.LayoutParams(-1, 0, 1f),
            )
            return
        }
        val scroll = ScrollView(this)
        val list = column()
        scroll.addView(list)
        visibleDevices()
            .filterNot { it.family }
            .sortedWith(
                compareByDescending<AppleDevice> { it.located }
                    .thenByDescending { it.online }
                    .thenBy { it.name }
            )
            .forEach { addDeviceRow(list, it) }
        val family = visibleDevices().filter { it.family }
        if (family.isNotEmpty()) {
            repository.familyMembers.forEach { member ->
                val memberDevices = family.filter { it.ownerId == member.id }
                if (memberDevices.isNotEmpty()) {
                    list.addView(
                        label("${member.name}のデバイス", 15, Ui.ink, true).apply {
                            setPadding(dp(22), dp(22), dp(22), dp(8))
                        }
                    )
                    memberDevices
                        .sortedWith(
                            compareByDescending<AppleDevice> { it.located }.thenBy { it.name }
                        )
                        .forEach { addDeviceRow(list, it) }
                }
            }
        }
        body.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun renderSharedList() {
        val title = if (activeTab == 0) "人を探す" else "持ち物を探す"
        val header =
            LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(22), dp(2), dp(16), dp(12))
            }
        header.addView(label(title, 28, Ui.ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(
            roundButton("↻", "連携情報を更新").apply {
                elevation = 0f
                setTextColor(Ui.blue)
                setOnClickListener { locationSync.refresh() }
            },
            LinearLayout.LayoutParams(dp(44), dp(44)),
        )
        header.addView(
            roundButton("+", "連携データを追加").apply {
                elevation = 0f
                setTextColor(Ui.blue)
                setOnClickListener { showSourceSettings() }
            },
            LinearLayout.LayoutParams(dp(44), dp(44)),
        )
        body.addView(header)
        if (locationSync.error.isNotBlank())
            body.addView(
                label(locationSync.error, 12, 0xffd87520.toInt()).apply {
                    setPadding(dp(22), 0, dp(22), dp(8))
                }
            )
        val entries = sharedEntries()
        if (entries.isEmpty()) {
            val empty =
                column().apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(28), 0, dp(28), dp(20))
                }
            empty.addView(
                DeviceGlyph(this, if (activeTab == 0) "Person" else "Tag"),
                LinearLayout.LayoutParams(dp(64), dp(64)),
            )
            space(empty, 12)
            empty.addView(
                label(
                    if (locationSync.loading) "読み込んでいます…"
                    else if (activeTab == 0) "人の位置情報を連携" else "持ち物の位置情報を連携",
                    21,
                    Ui.ink,
                    true,
                )
            )
            space(empty, 10)
            empty.addView(
                label(
                        if (activeTab == 0) "共有済みの人の情報を、所有者が設定した連携元から表示します。"
                        else "AirTagなどの持ち物を、対応する連携データから表示します。",
                        14,
                        Ui.muted,
                    )
                    .apply { gravity = Gravity.CENTER }
            )
            space(empty, 18)
            empty.addView(
                actionButton("連携を設定").apply { setOnClickListener { showSourceSettings() } },
                LinearLayout.LayoutParams(-1, dp(50)),
            )
            body.addView(empty, LinearLayout.LayoutParams(-1, 0, 1f))
            return
        }
        if (locationSync.sourceName.isNotEmpty())
            body.addView(
                label(locationSync.sourceName, 11, Ui.muted).apply {
                    setPadding(dp(22), 0, dp(22), dp(8))
                }
            )
        val scroll = ScrollView(this)
        val list = column()
        scroll.addView(list)
        entries
            .sortedWith(compareByDescending<SharedLocation> { it.located }.thenBy { it.name })
            .forEach { addDeviceRow(list, it.asDevice()) }
        body.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun renderSignIn() {
        val content =
            column().apply {
                gravity = Gravity.CENTER
                setPadding(dp(30), dp(4), dp(30), dp(16))
            }
        content.addView(DeviceGlyph(this, "iPhone"), LinearLayout.LayoutParams(dp(52), dp(52)))
        space(content, 10)
        content.addView(label("Appleデバイスを探す", 22, Ui.ink, true).apply { gravity = Gravity.CENTER })
        space(content, 8)
        content.addView(
            label("Apple Accountに接続すると、\nデバイスの位置をこの地図に表示します。", 14, Ui.muted).apply {
                gravity = Gravity.CENTER
            }
        )
        space(content, 16)
        content.addView(
            actionButton("Apple Accountでサインイン").apply {
                id = OPEN_FIND_ID
                setOnClickListener { connect() }
            },
            LinearLayout.LayoutParams(-1, dp(50)),
        )
        space(content, 8)
        content.addView(
            label("認証にはAppleの公式画面を使用します", 11, Ui.muted).apply { gravity = Gravity.CENTER }
        )
        body.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun addDeviceRow(parent: LinearLayout, device: AppleDevice) {
        val row =
            LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(21), dp(12), dp(20), dp(12))
                minimumHeight = dp(84)
                background = Ui.ripple(Color.TRANSPARENT, 0)
            }
        row.addView(DeviceGlyph(this, device.kind), LinearLayout.LayoutParams(dp(48), dp(52)))
        val labels = column()
        labels.addView(
            label(device.name, 17, Ui.ink, true).apply {
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            }
        )
        labels.addView(
            label(device.subtitle(), 12, Ui.muted).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
        )
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(15) })
        val tail = column().apply { gravity = Gravity.END }
        if (device.battery != null)
            tail.addView(
                label(
                    device.batteryText(),
                    10,
                    if (device.battery < .2) 0xffff453a.toInt() else Ui.muted,
                )
            )
        tail.addView(label("›", 25, 0xffc7c7cc.toInt()))
        row.addView(tail)
        row.contentDescription = "${device.name}、${device.status()}"
        row.setOnClickListener { select(device.id) }
        parent.addView(row, LinearLayout.LayoutParams(-1, -2))
        parent.addView(
            View(this).apply { setBackgroundColor(Ui.separator) },
            LinearLayout.LayoutParams(-1, dp(.5f)).apply { leftMargin = dp(84) },
        )
    }

    private fun renderDetails(device: AppleDevice) {
        val scroll = ScrollView(this)
        val content = column().apply { setPadding(dp(22), 0, dp(22), dp(24)) }
        scroll.addView(content)
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(DeviceGlyph(this, device.kind), LinearLayout.LayoutParams(dp(40), dp(45)))
        header.addView(
            label(device.name, 24, Ui.ink, true),
            LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) },
        )
        header.addView(
            roundButton("×", "デバイスの一覧に戻る").apply {
                elevation = 0f
                setTextColor(Ui.muted)
                setOnClickListener {
                    selectedId = null
                    renderSheet()
                    renderMarkers()
                }
            },
            LinearLayout.LayoutParams(dp(40), dp(40)),
        )
        content.addView(header)
        space(content, 9)
        if (device.family) content.addView(label("${device.ownerName}のデバイス", 13, Ui.muted))
        content.addView(label(device.model, 13, Ui.muted))
        if (device.address.isNotBlank()) content.addView(label(device.address, 15))
        space(content, 5)
        content.addView(
            label(
                device.status() +
                    (if (device.battery != null) "   ▰ ${device.batteryText()}" else ""),
                13,
                if (device.located) Ui.green else Ui.muted,
            )
        )
        space(content, 20)
        val actions = LinearLayout(this)
        if (activeTab != 0)
            actions.addView(
                actionTile("◖))", "サウンドを再生", "テスト中は無効", 0xff6052de.toInt()).apply {
                    id = SOUND_ID
                    isEnabled = false
                    alpha = .5f
                    contentDescription = "サウンドを再生、デバッグ中は無効"
                },
                LinearLayout.LayoutParams(0, dp(110), 1f),
            )
        if (activeTab != 0) actions.addView(View(this), LinearLayout.LayoutParams(dp(12), 1))
        actions.addView(
            actionTile("➤", "経路", "マップで開く", Ui.blue).apply {
                isEnabled = device.located
                alpha = if (device.located) 1f else .4f
                setOnClickListener { openDirections(device) }
            },
            LinearLayout.LayoutParams(0, dp(110), 1f),
        )
        content.addView(actions)
        space(content, 20)
        content.addView(
            label("位置情報を更新", 16, Ui.blue).apply {
                minHeight = dp(48)
                gravity = Gravity.CENTER_VERTICAL
                setOnClickListener {
                    if (
                        activeTab == 0 ||
                            activeTab == 2 ||
                            device in locationSync.pencils.map { it.asDevice() }
                    )
                        locationSync.refresh()
                    else repository.refresh()
                }
            }
        )
        val shared = sharedEntries().firstOrNull { it.id == device.id }
        content.addView(label(shared?.source ?: "位置情報の表示に対応 · サウンドは無効", 11, Ui.muted))
        body.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun actionTile(icon: String, title: String, detail: String, color: Int) =
        column().apply {
            setPadding(dp(16), dp(13), dp(12), dp(12))
            background = GlassDrawable(glass, this, dp(22).toFloat(), .38f)
            elevation = dp(1).toFloat()
            addView(label(icon, 23, color, true))
            space(this, 5)
            addView(label(title, 15, Ui.ink, true))
            addView(label(detail, 11, Ui.muted))
        }

    private fun renderAccount() {
        val content = column().apply { setPadding(dp(24), 0, dp(24), dp(20)) }
        content.addView(label("自分", 28, Ui.ink, true))
        space(content, 20)
        content.addView(
            label(
                if (repository.hasSession) "Apple Accountに接続済み" else "Apple Accountに未接続",
                18,
                Ui.ink,
                true,
            )
        )
        space(content, 8)
        content.addView(label("このAndroidを「探す」ネットワークへ登録することはできません。", 13, Ui.muted))
        space(content, 20)
        content.addView(
            actionButton(if (repository.hasSession) "Apple Accountに再接続" else "Apple Accountでサインイン")
                .apply {
                    id = OPEN_FIND_ID
                    setOnClickListener { connect() }
                },
            LinearLayout.LayoutParams(-1, dp(50)),
        )
        space(content, 12)
        if (repository.hasSession)
            content.addView(
                actionButton("サインアウト", false).apply { setOnClickListener { confirmLogout() } },
                LinearLayout.LayoutParams(-1, dp(48)),
            )
        space(content, 12)
        content.addView(
            actionButton("外観と地図", false).apply { setOnClickListener { showMapSettings() } },
            LinearLayout.LayoutParams(-1, dp(48)),
        )
        space(content, 10)
        content.addView(
            actionButton("人・持ち物の連携", false).apply { setOnClickListener { showSourceSettings() } },
            LinearLayout.LayoutParams(-1, dp(48)),
        )
        space(content, 16)
        content.addView(
            label("Find Bridge ${BuildConfig.VERSION_NAME} · 非公式クライアント", 12, Ui.muted).apply {
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Find Bridge")
                        .setMessage(
                            "地図とデバイス画面はKotlinのネイティブUIです。Apple認証にだけ公式ページを使用します。\n\nAppleとは提携していません。地図データはOpenStreetMapです。\n\nAndroid 17 / API 37\nMapLibre Native 13.7.1\nAndroidX WebKit 1.18.0\n\n音を鳴らす・ロック・消去のAPIは実装していません。"
                        )
                        .setPositiveButton("閉じる", null)
                        .show()
                }
            }
        )
        body.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun connect() {
        startActivityForResult(Intent(this, AuthActivity::class.java), 10)
    }

    private fun accountMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu
                .add(if (repository.hasSession) "Apple Accountに再接続" else "Apple Accountでサインイン")
                .setOnMenuItemClickListener {
                    connect()
                    true
                }
            if (repository.hasSession)
                menu.add("サインアウト").setOnMenuItemClickListener {
                    confirmLogout()
                    true
                }
            show()
        }
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("サインアウトしますか？")
            .setMessage("このアプリのログイン状態と取得した位置情報を削除します。Appleのデバイスは削除されません。")
            .setNegativeButton("キャンセル", null)
            .setPositiveButton("サインアウト") { _, _ ->
                selectedId = null
                fitted = false
                repository.logout()
            }
            .show()
    }

    private fun selected() = visibleDevices().firstOrNull { it.id == selectedId }

    private fun select(id: String) {
        selectedId = id
        accountTab = false
        renderSheet()
        updateTabs()
        renderMarkers()
        selected()
            ?.takeIf { it.located }
            ?.let {
                map?.animateCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder()
                            .target(LatLng(it.latitude!!, it.longitude!!))
                            .zoom(15.0)
                            .padding(
                                dp(25).toDouble(),
                                (topInset + dp(35)).toDouble(),
                                dp(25).toDouble(),
                                (sheetHeight + dp(64) + bottomInset + dp(30)).toDouble(),
                            )
                            .build()
                    ),
                    650,
                )
            }
    }

    private fun renderMarkers() {
        val current = map ?: return
        if (current.style?.isFullyLoaded != true) return
        current.clear()
        markers.clear()
        visibleDevices()
            .filter { it.located }
            .forEach { device ->
                val marker =
                    current.addMarker(
                        MarkerOptions()
                            .position(LatLng(device.latitude!!, device.longitude!!))
                            .icon(
                                IconFactory.getInstance(this)
                                    .fromBitmap(
                                        DeviceGlyph.marker(
                                            this,
                                            device.kind,
                                            device.id == selectedId,
                                        )
                                    )
                            )
                    )
                markers[marker] = device.id
            }
    }

    private fun fitDevices(force: Boolean = false) {
        val current = map ?: return
        if (fitted && !force) return
        if (current.style?.isFullyLoaded != true || mapView.width == 0 || root.height == 0) return
        val located = visibleDevices().filter { it.located }
        val points =
            located
                .filterNot { it.family }
                .ifEmpty { located }
                .map { LatLng(it.latitude!!, it.longitude!!) }
        if (points.isEmpty()) return
        layoutOverlays()
        current.moveCamera(CameraUpdateFactory.paddingTo(0.0, 0.0, 0.0, 0.0))
        val update =
            if (points.size == 1)
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(points.first())
                        .zoom(14.0)
                        .padding(
                            dp(40).toDouble(),
                            (topInset + dp(60)).toDouble(),
                            dp(40).toDouble(),
                            (sheetHeight + dp(64) + bottomInset + dp(40)).toDouble(),
                        )
                        .build()
                )
            else
                CameraUpdateFactory.newLatLngBounds(
                    LatLngBounds.Builder().includes(points).build(),
                    dp(40),
                    topInset + dp(60),
                    dp(40),
                    sheetHeight + dp(64) + bottomInset + dp(40),
                )
        current.animateCamera(update, 700)
        fitted = true
    }

    private fun openDirections(device: AppleDevice) {
        if (!device.located) return
        val uri =
            Uri.parse(
                "geo:${device.latitude},${device.longitude}?q=${device.latitude},${device.longitude}(${Uri.encode(device.name)})"
            )
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "地図アプリをインストールすると経路を表示できます", Toast.LENGTH_LONG).show()
        }
    }

    override fun onChanged() {
        statusPill.text =
            if (repository.loading || locationSync.loading) "更新中…"
            else if (repository.hasSession || locationSync.configured) "探す" else "Find Bridge"
        renderSheet()
        renderMarkers()
        fitDevices()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 10 && resultCode == RESULT_OK) {
            fitted = false
            repository.refresh()
        }
        if (requestCode == 20 && resultCode == RESULT_OK)
            data?.data?.let { uri ->
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                locationSync.importFile(uri)
            }
    }

    private fun back() {
        if (selectedId != null || activeTab != 1) {
            selectedId = null
            accountTab = false
            activeTab = 1
            renderSheet()
            updateTabs()
            renderMarkers()
        } else finish()
    }

    @SuppressLint("GestureBackNavigation") override fun onBackPressed() = back()

    override fun onStart() {
        super.onStart()
        mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        onChanged()
        repository.refresh()
        locationSync.refresh()
        handler.postDelayed(periodicRefresh, 30_000)
    }

    override fun onPause() {
        handler.removeCallbacks(periodicRefresh)
        mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        mapView.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        repository.removeListener(this)
        locationSync.removeListener(this)
        glass.dispose()
        mapView.onDestroy()
        super.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
        outState.putString("selected", selectedId)
        outState.putInt("tab", activeTab)
    }

    companion object {
        const val OPEN_FIND_ID = 1001
        const val MAP_ID = 1003
        const val REFRESH_ID = 1004
        const val SHEET_ID = 1005
        const val SOUND_ID = 1006
    }
}
