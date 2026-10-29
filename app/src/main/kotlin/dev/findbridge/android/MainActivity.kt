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
                handler.postDelayed(this, 30_000)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        repository = AppleRepository.get(this)
        root =
            FrameLayout(this).apply {
                setBackgroundColor(0xffeae9e4.toInt())
                systemUiVisibility =
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
        if (android.os.Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        mapView =
            MapView(this).apply {
                id = MAP_ID
                onCreate(savedInstanceState)
            }
        root.addView(mapView, FrameLayout.LayoutParams(-1, -1))
        mapView.getMapAsync { value ->
            map = value
            value.uiSettings.apply {
                isLogoEnabled = false
                isAttributionEnabled = false
                isCompassEnabled = false
            }
            value.setMinZoomPreference(2.0)
            value.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(36.2, 138.2), 4.5))
            value.setStyle(Style.Builder().fromUri("asset://map-style.json")) {
                renderMarkers()
                fitDevices()
            }
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
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                ::back,
            )
        }
        repository.addListener(this)
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
            background = Ui.ripple(0xfaffffff.toInt(), dp(16))
            stateListAnimator = null
            elevation = dp(3).toFloat()
            contentDescription = description
        }

    private fun createMapControls() {
        controls = column()
        controls.addView(
            roundButton("▱", "地図の表示設定").apply {
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("地図")
                        .setMessage(
                            "標準地図 · ネイティブ描画\n\n© OpenFreeMap\n© OpenMapTiles\n© OpenStreetMap contributors"
                        )
                        .setPositiveButton("完了", null)
                        .show()
                }
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
                background = Ui.rounded(0xf7ffffff.toInt(), dp(22))
                elevation = dp(2).toFloat()
            }
        root.addView(statusPill, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
        attribution =
            label("© OpenFreeMap · OpenMapTiles · OpenStreetMap", 9, 0xff555555.toInt()).apply {
                setPadding(dp(5), dp(2), dp(5), dp(2))
                background = Ui.rounded(0xeaffffff.toInt(), dp(5))
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("地図の出典")
                        .setMessage(
                            "© OpenFreeMap · openfreemap.org\n© OpenMapTiles · openmaptiles.org\n© OpenStreetMap contributors · openstreetmap.org/copyright"
                        )
                        .setPositiveButton("閉じる", null)
                        .show()
                }
            }
        root.addView(attribution, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START))
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createSheet() {
        sheetHeight = dp(390)
        sheet =
            column().apply {
                id = SHEET_ID
                background =
                    Ui.rounded(Ui.surface, dp(26)).apply {
                        cornerRadii =
                            floatArrayOf(
                                dp(26).toFloat(),
                                dp(26).toFloat(),
                                dp(26).toFloat(),
                                dp(26).toFloat(),
                                0f,
                                0f,
                                0f,
                                0f,
                            )
                    }
                elevation = dp(5).toFloat()
                clipToOutline = true
            }
        val handleArea =
            column().apply {
                gravity = Gravity.CENTER
                contentDescription = "デバイス一覧の高さを変更"
            }
        handleArea.addView(
            View(this).apply { background = Ui.rounded(0xffc5c5ca.toInt(), dp(3)) },
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
                gravity = Gravity.TOP
                setBackgroundColor(0xfffafafc.toInt())
                elevation = dp(7).toFloat()
            }
        addTab(0, "人を探す") { unsupported("人を探す") }
        addTab(1, "デバイス") {
            accountTab = false
            selectedId = null
            renderSheet()
            updateTabs()
        }
        addTab(2, "持ち物") { unsupported("持ち物を探す") }
        addTab(3, "自分") {
            accountTab = true
            selectedId = null
            renderSheet()
            updateTabs()
        }
        root.addView(tabs, FrameLayout.LayoutParams(-1, dp(64), Gravity.BOTTOM))
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
        tabs.addView(tab, LinearLayout.LayoutParams(0, dp(62), 1f))
    }

    private fun updateTabs() {
        for (index in 0 until tabs.childCount) {
            val selected = if (accountTab) index == 3 else index == 1
            val tab = tabs.getChildAt(index) as LinearLayout
            val color = if (selected) Ui.green else Ui.muted
            (tab.getChildAt(0) as TabGlyph).tint = color
            (tab.getChildAt(1) as TextView).setTextColor(color)
        }
    }

    private fun unsupported(feature: String) {
        AlertDialog.Builder(this)
            .setTitle(feature)
            .setMessage("このアプリではiPhone・iPad・Macなどの「デバイスを探す」に対応しています。")
            .setPositiveButton("閉じる", null)
            .show()
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
        val tabHeight = dp(64) + bottomInset
        (tabs.layoutParams as FrameLayout.LayoutParams).apply {
            height = tabHeight
            tabs.layoutParams = this
        }
        tabs.setPadding(0, 0, 0, bottomInset)
        (sheet.layoutParams as FrameLayout.LayoutParams).apply {
            height = sheetHeight
            bottomMargin = tabHeight
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
        if (!repository.hasSession) {
            renderSignIn()
            return
        }
        if (repository.devices.isEmpty()) {
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
        repository.devices
            .filterNot { it.family }
            .sortedWith(
                compareByDescending<AppleDevice> { it.located }
                    .thenByDescending { it.online }
                    .thenBy { it.name }
            )
            .forEach { addDeviceRow(list, it) }
        val family = repository.devices.filter { it.family }
        if (family.isNotEmpty()) {
            list.addView(
                label("ファミリーのデバイス", 13, Ui.muted).apply {
                    setPadding(dp(22), dp(16), dp(22), dp(6))
                }
            )
            family.forEach { addDeviceRow(list, it) }
        }
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
                background = Ui.ripple(Ui.surface, 0)
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
            View(this).apply { setBackgroundColor(0xffe5e5ea.toInt()) },
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
        actions.addView(
            actionTile("◖))", "サウンドを再生", "テスト中は無効", 0xff6052de.toInt()).apply {
                id = SOUND_ID
                isEnabled = false
                alpha = .5f
                contentDescription = "サウンドを再生、デバッグ中は無効"
            },
            LinearLayout.LayoutParams(0, dp(110), 1f),
        )
        actions.addView(View(this), LinearLayout.LayoutParams(dp(12), 1))
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
                setOnClickListener { repository.refresh() }
            }
        )
        content.addView(label("位置情報の表示に対応 · サウンドはデバッグ中無効", 11, Ui.muted))
        body.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun actionTile(icon: String, title: String, detail: String, color: Int) =
        column().apply {
            setPadding(dp(16), dp(13), dp(12), dp(12))
            background = Ui.rounded(Color.WHITE, dp(18))
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
        space(content, 16)
        content.addView(
            label("Find Bridge 2.0 · 非公式クライアント", 12, Ui.muted).apply {
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

    private fun selected() = repository.devices.firstOrNull { it.id == selectedId }

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
        repository.devices
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
        val located = repository.devices.filter { it.located }
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
            if (repository.loading) "更新中…" else if (repository.hasSession) "探す" else "Find Bridge"
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
    }

    private fun back() {
        if (selectedId != null || accountTab) {
            selectedId = null
            accountTab = false
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
    }

    companion object {
        const val OPEN_FIND_ID = 1001
        const val MAP_ID = 1003
        const val REFRESH_ID = 1004
        const val SHEET_ID = 1005
        const val SOUND_ID = 1006
    }
}
