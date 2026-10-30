@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package dev.findbridge.android

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.util.Consumer
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.window.java.layout.WindowInfoTrackerCallbackAdapter
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import dev.findbridge.android.Ui.actionButton
import dev.findbridge.android.Ui.column
import dev.findbridge.android.Ui.dp
import dev.findbridge.android.Ui.label
import dev.findbridge.android.Ui.space
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.Icon
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
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    private lateinit var repository: AppleRepository
    private lateinit var google: GoogleRepository
    private lateinit var loginVault: LoginVault
    private var foreground = false
    private var connecting = false
    private lateinit var locationSync: LocationSyncRepository
    private lateinit var appearance: AppearanceSettings
    private lateinit var glass: GlassScene
    private lateinit var frameRate: MapFrameRate
    private lateinit var root: FrameLayout
    private lateinit var mapView: MapView
    private var map: MapLibreMap? = null
    private lateinit var sheet: SheetGestureLayout
    private lateinit var body: LinearLayout
    private lateinit var bodyHost: FrameLayout
    private lateinit var pageMotion: PageMotion
    private lateinit var tabs: NativeTabBar
    private lateinit var controls: LinearLayout
    private lateinit var mapButton: MapControlButton
    private lateinit var recenterButton: MapControlButton
    private var panel: GlassPanel? = null
    private lateinit var attribution: TextView
    private lateinit var statusPill: TextView
    private val markers = mutableMapOf<Marker, String>()
    private val markerById = mutableMapOf<String, Marker>()
    private val markerKinds = mutableMapOf<String, String>()
    private val iconCache = mutableMapOf<String, Icon>()
    private lateinit var selectedPin: ImageView
    private var pinId: String? = null
    private var selectedId: String? = null
    private var accountTab = false
    private var activeTab = 1
    private var fitted = false
    private var restoredCamera: DoubleArray? = null
    private var topInset = 0
    private var bottomInset = 0
    private var leftInset = 0
    private var rightInset = 0
    private var foldRegion: FoldRegion? = null
    private var lastLayoutMode: LayoutMode? = null
    private var windowAdapter: WindowInfoTrackerCallbackAdapter? = null
    private val foldListener =
        Consumer<WindowLayoutInfo> { info ->
            val feature = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
            val region = feature?.let {
                val b = it.bounds
                FoldRegion(
                    ScreenRect(b.left, b.top, b.right, b.bottom),
                    it.orientation == FoldingFeature.Orientation.VERTICAL,
                    it.isSeparating || it.state == FoldingFeature.State.HALF_OPENED,
                )
            }
            if (region != foldRegion) {
                foldRegion = region
                panel?.dismiss(immediate = true)
                layoutOverlays()
                selected()?.let { focusDevice(it, sheetHeight.toFloat()) }
            }
        }
    private var sheetHeight = 0
    private var sheetSpring: SpringAnimation? = null
    private var dragHeight = 0f
    private var listHeight = 0
    private var renderedPage = ""
    private var pendingRender = false
    private val scrollPositions = mutableMapOf<String, Int>()
    private val handler = Handler(Looper.getMainLooper())
    private val periodicRefresh =
        object : Runnable {
            override fun run() {
                repository.refresh()
                google.refreshIfDue()
                locationSync.refresh()
                handler.postDelayed(this, 30_000)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        Ui.configure(this)
        if (Ui.dark) setTheme(R.style.Theme_FindBridge_Dark)
        super.onCreate(savedInstanceState)
        appearance = AppearanceSettings(this)
        restoredCamera = savedInstanceState?.getDoubleArray("mapCamera")
        sheetHeight = savedInstanceState?.getInt("sheetHeight", dp(390)) ?: dp(390)
        listHeight = savedInstanceState?.getInt("listHeight", dp(390)) ?: dp(390)
        savedInstanceState?.getBundle("scrollPositions")?.let { positions ->
            positions.keySet().forEach { scrollPositions[it] = positions.getInt(it) }
        }
        MapLibre.getInstance(this)
        repository = AppleRepository.get(this)
        google = GoogleRepository.get(this)
        loginVault = LoginVault(this)
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
        selectedPin =
            ImageView(this).apply {
                id = SELECTED_PIN_ID
                visibility = View.INVISIBLE
                isSoundEffectsEnabled = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setOnClickListener { selectedId?.let(::select) }
            }
        root.addView(selectedPin, FrameLayout.LayoutParams(dp(66), dp(74)))
        glass = GlassScene(mapView).apply { intensity = appearance.glassIntensity }
        frameRate = MapFrameRate(this, mapView)
        mapView.getMapAsync { value ->
            map = value
            value.uiSettings.apply {
                isLogoEnabled = false
                isAttributionEnabled = false
                isCompassEnabled = false
            }
            value.setMinZoomPreference(2.0)
            val savedCamera = restoredCamera
            if (savedCamera != null && savedCamera.size == 5) {
                value.moveCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder()
                            .target(LatLng(savedCamera[0], savedCamera[1]))
                            .zoom(savedCamera[2])
                            .bearing(savedCamera[3])
                            .tilt(savedCamera[4])
                            .build()
                    )
                )
                fitted = true
            } else value.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(36.2, 138.2), 4.5))
            applyMapStyle()
            value.addOnCameraMoveStartedListener {
                frameRate.setMoving(true)
                glass.startTracking(value)
            }
            value.addOnCameraMoveListener {
                positionSelectedPin()
            }
            value.addOnCameraIdleListener {
                glass.stopTracking()
                glass.trackCamera(value)
                frameRate.setMoving(false)
                glass.capture(value, immediate = true)
            }
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
                leftInset = bars.left
                rightInset = bars.right
            } else {
                topInset = insets.systemWindowInsetTop
                bottomInset = insets.systemWindowInsetBottom
                leftInset = insets.systemWindowInsetLeft
                rightInset = insets.systemWindowInsetRight
            }
            layoutOverlays()
            insets
        }
        setContentView(root)
        root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) {
                panel?.dismiss(immediate = true)
                layoutOverlays()
            }
        }
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
        google.addListener(this)
        locationSync.addListener(this)
        renderSheet()
        if (savedInstanceState == null)
            root.post {
                NativeMotion.enter(sheet, dp(30).toFloat())
                NativeMotion.enter(tabs, dp(22).toFloat(), 40)
                NativeMotion.enter(controls, dp(-10).toFloat(), 65)
            }
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
            NativeMotion.press(this)
        }

    private fun createMapControls() {
        controls = column()
        mapButton =
            MapControlButton(this, glass, MapControlButton.Symbol.MAP).apply {
                id = MAP_OPTIONS_ID
                contentDescription = getString(R.string.map_options)
                setOnClickListener { showMapSettings() }
            }
        recenterButton =
            MapControlButton(this, glass, MapControlButton.Symbol.LOCATION).apply {
                id = RECENTER_ID
                contentDescription = getString(R.string.show_all_devices)
                setOnClickListener { fitDevices(force = true) }
            }
        controls.addView(mapButton, LinearLayout.LayoutParams(dp(44), dp(44)))
        space(controls, 8)
        controls.addView(recenterButton, LinearLayout.LayoutParams(dp(44), dp(44)))
        root.addView(controls, FrameLayout.LayoutParams(dp(44), -2, Gravity.TOP or Gravity.END))
        statusPill =
            label(getString(R.string.find_title), 14, Ui.ink, true).apply {
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
                        .setTitle(getString(R.string.map_credits))
                        .setMessage(getString(R.string.map_credits_body))
                        .setPositiveButton(getString(R.string.close), null)
                        .show()
                }
            }
        root.addView(attribution, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START))
    }

    private fun applyMapStyle() {
        val current = map ?: return
        mapButton.symbol =
            if (appearance.mapMode == MapMode.STANDARD) MapControlButton.Symbol.MAP
            else MapControlButton.Symbol.SATELLITE
        mapButton.invalidate()
        fitted = false
        current.setStyle(
            Style.Builder().fromJson(MapStyles.create(this, appearance.mapMode, Ui.dark))
        ) {
            current.clear()
            markers.clear()
            markerById.clear()
            markerKinds.clear()
            renderMarkers()
            if (restoredCamera != null) {
                restoredCamera = null
                fitted = true
                layoutSheet(updateCamera = true)
            } else if (selectedId != null)
                selected()?.let { focusDevice(it, sheetHeight.toFloat()) }
            else fitDevices()
            glass.capture(current, immediate = true)
            if (::attribution.isInitialized)
                attribution.text =
                    when (appearance.mapMode) {
                        MapMode.STANDARD -> "© OpenFreeMap · OpenMapTiles · OpenStreetMap"
                        MapMode.AERIAL -> getString(R.string.gsi_source)
                        MapMode.HYBRID -> getString(R.string.gsi_hybrid_source)
                    }
        }
    }

    private fun showMapSettings() {
        val content = column().apply { setPadding(dp(22), dp(8), dp(22), dp(12)) }
        content.addView(label(getString(R.string.map_view), 20, Ui.ink, true))
        space(content, 14)
        val modes = LinearLayout(this)
        val buttons = mutableListOf<Button>()
        MapMode.entries.forEach { mode ->
            val button =
                actionButton(getString(mode.titleRes), appearance.mapMode == mode).apply {
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
        content.addView(label(getString(R.string.appearance), 16, Ui.ink, true))
        space(content, 6)
        val theme = actionButton(getString(appearance.appearance.titleRes), false)
        content.addView(theme, LinearLayout.LayoutParams(-1, dp(48)))
        theme.setOnClickListener { showAppearanceSettings() }
        space(content, 14)
        content.addView(label(getString(R.string.glass_intensity), 16, Ui.ink, true))
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
        content.addView(label(getString(R.string.imagery_help), 11, Ui.muted))
        space(content, 14)
        content.addView(
            Switch(this).apply {
                text = getString(R.string.reduce_motion)
                textSize = 15f
                setTextColor(Ui.ink)
                isChecked = appearance.reduceMotion
                setOnCheckedChangeListener { _, checked -> appearance.reduceMotion = checked }
            },
            LinearLayout.LayoutParams(-1, dp(48)),
        )
        content.addView(label(getString(R.string.motion_help), 11, Ui.muted))
        space(content, 14)
        content.addView(label(getString(R.string.language), 16, Ui.ink, true))
        content.addView(
            actionButton(AppLanguage.label(this), false).apply {
                setOnClickListener { showLanguageSettings() }
            },
            LinearLayout.LayoutParams(-1, dp(48)),
        )
        presentPanel(content, mapButton)
    }

    private fun showLanguageSettings() {
        val content = column().apply { setPadding(dp(22), dp(12), dp(22), dp(12)) }
        content.addView(label(getString(R.string.language), 22, Ui.ink, true))
        space(content, 14)
        AppLanguage.choices.forEach { (tag, name) ->
            content.addView(
                actionButton(
                        if (tag.isEmpty()) getString(R.string.system) else name,
                        tag == AppLanguage.selected(this),
                    )
                    .apply {
                        setOnClickListener {
                            panel?.dismiss(immediate = true)
                            AppLanguage.select(this@MainActivity, tag)
                        }
                    },
                LinearLayout.LayoutParams(-1, dp(48)),
            )
            space(content, 6)
        }
        presentPanel(content, mapButton)
    }

    private fun showAppearanceSettings() {
        val content = column().apply { setPadding(dp(22), dp(12), dp(22), dp(12)) }
        content.addView(label(getString(R.string.appearance), 22, Ui.ink, true))
        space(content, 16)
        Appearance.entries.forEach { choice ->
            content.addView(
                actionButton(getString(choice.titleRes), appearance.appearance == choice).apply {
                    setOnClickListener {
                        appearance.appearance = choice
                        recreate()
                    }
                },
                LinearLayout.LayoutParams(-1, dp(50)),
            )
            space(content, 8)
        }
        presentPanel(content, mapButton)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createSheet() {
        sheet =
            SheetGestureLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                id = SHEET_ID
                background = GlassDrawable(glass, this, dp(32).toFloat(), .72f)
                elevation = dp(9).toFloat()
                clipToOutline = true
                canExpand = { sheetHeight < sheetStops().last() - dp(8) }
                onDragStart = {
                    sheetSpring?.cancel()
                    dragHeight = sheetHeight.toFloat()
                }
                onDragBy = { delta ->
                    dragHeight += delta
                    val stops = sheetStops()
                    sheetHeight =
                        SheetDetents.resist(
                                dragHeight,
                                stops.first(),
                                stops.last(),
                                dp(110).toFloat(),
                            )
                            .toInt()
                    layoutSheet(updateCamera = true)
                }
                onDragEnd = { velocity ->
                    val stops = sheetStops()
                    animateSheet(
                        SheetDetents.target(
                            sheetHeight.toFloat(),
                            velocity,
                            stops,
                            dp(520).toFloat(),
                        ),
                        velocity,
                        true,
                    )
                }
            }
        val handleArea =
            column().apply {
                gravity = Gravity.CENTER
                id = SHEET_HANDLE_ID
                contentDescription = getString(R.string.resize_sheet)
                isSoundEffectsEnabled = false
            }
        handleArea.addView(
            View(this).apply {
                background =
                    Ui.rounded(if (Ui.dark) 0xff636369.toInt() else 0xffc1c1c8.toInt(), dp(3))
            },
            LinearLayout.LayoutParams(dp(36), dp(5)),
        )
        handleArea.setOnClickListener {
            val stops = sheetStops()
            animateSheet(
                if (sheetHeight < stops[1] + dp(35)) stops.last() else stops[1],
                followCamera = true,
            )
        }
        sheet.addView(handleArea, LinearLayout.LayoutParams(-1, dp(25)))
        bodyHost = FrameLayout(this)
        body = column()
        bodyHost.addView(body, FrameLayout.LayoutParams(-1, -1))
        pageMotion =
            PageMotion(bodyHost).apply {
                onSettled = {
                    if (pendingRender) {
                        pendingRender = false
                        renderSheet()
                    }
                }
            }
        sheet.addView(bodyHost, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(sheet, FrameLayout.LayoutParams(-1, sheetHeight, Gravity.BOTTOM))
    }

    private fun createTabs() {
        tabs = NativeTabBar(this, glass, ::switchTab)
        root.addView(tabs, FrameLayout.LayoutParams(-1, dp(68), Gravity.BOTTOM))
    }

    private fun updateTabs(animated: Boolean = true) {
        tabs.update(activeTab, animated)
    }

    private fun switchTab(index: Int) {
        if (index == activeTab && selectedId == null) return
        val previous = activeTab
        activeTab = index
        accountTab = index == 3
        selectedId = null
        fitted = false
        renderSheet(if (index < previous) PageChange.TAB_LEFT else PageChange.TAB_RIGHT)
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
            1 -> repository.devices + google.devices + locationSync.pencils.map { it.asDevice() }
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
        content.addView(label(getString(R.string.location_sources), 22, Ui.ink, true))
        space(content, 12)
        content.addView(
            label(
                getString(R.string.source_help),
                14,
                Ui.muted,
            )
        )
        space(content, 18)
        content.addView(
            actionButton(getString(R.string.choose_source)).apply {
                setOnClickListener { chooseLocationFile() }
            },
            LinearLayout.LayoutParams(-1, dp(50)),
        )
        if (locationSync.configured) {
            space(content, 10)
            content.addView(
                actionButton(getString(R.string.disconnect_source), false).apply {
                    setOnClickListener { locationSync.disconnect() }
                },
                LinearLayout.LayoutParams(-1, dp(48)),
            )
        }
        presentPanel(content)
    }

    private fun currentLayout(targetHeight: Int = sheetHeight): LayoutPlan =
        AdaptiveLayout.calculate(
            root.width,
            root.height,
            resources.displayMetrics.density,
            ScreenInsets(leftInset, topInset, rightInset, bottomInset),
            targetHeight,
            foldRegion,
        )

    @SuppressLint("RtlHardcoded") // FoldingFeature bounds use physical window coordinates.
    private fun place(view: View, area: ScreenRect) {
        val params = view.layoutParams as FrameLayout.LayoutParams
        if (
            params.width == area.width &&
                params.height == area.height &&
                params.leftMargin == area.left &&
                params.topMargin == area.top &&
                params.gravity == (Gravity.TOP or Gravity.LEFT)
        )
            return
        params.width = area.width
        params.height = area.height
        params.leftMargin = area.left
        params.topMargin = area.top
        params.bottomMargin = 0
        params.rightMargin = 0
        params.gravity = Gravity.TOP or Gravity.LEFT
        view.layoutParams = params
    }

    private fun layoutOverlays() {
        if (root.width == 0 || root.height == 0) return
        val plan = currentLayout()
        if (lastLayoutMode != plan.mode) {
            sheetSpring?.cancel()
            panel?.dismiss(immediate = true)
            lastLayoutMode = plan.mode
        }
        if (plan.mode == LayoutMode.PHONE)
            sheetHeight =
                sheetHeight.coerceIn(sheetStops().first().toInt(), sheetStops().last().toInt())
        place(mapView, plan.map)
        place(controls, plan.controls)
        tabs.setVertical(plan.mode == LayoutMode.RAIL)
        place(tabs, plan.tabs)
        statusPill.visibility = if (plan.sidebar) View.INVISIBLE else View.VISIBLE
        (statusPill.layoutParams as FrameLayout.LayoutParams).apply {
            topMargin = topInset + dp(17)
            leftMargin = leftInset + dp(16)
            statusPill.layoutParams = this
        }
        sheet.dragEnabled = plan.mode == LayoutMode.PHONE
        sheet.findViewById<View>(SHEET_HANDLE_ID).visibility =
            if (sheet.dragEnabled) View.VISIBLE else View.GONE
        layoutSheet(updateCamera = true)
    }

    private fun sheetStops(): List<Float> {
        val maximum =
            (root.height - topInset - bottomInset - dp(156)).coerceAtLeast(dp(110)).toFloat()
        val minimum = minOf(dp(138).toFloat(), maximum)
        return listOf(minimum, minOf(dp(390).toFloat(), maximum), maximum)
    }

    private fun animateSheet(target: Float, velocity: Float = 0f, followCamera: Boolean = false) {
        if (currentLayout().mode != LayoutMode.PHONE) return
        val destination = target.coerceIn(sheetStops().first(), sheetStops().last())
        if (NativeMotion.reduced(this)) {
            sheetSpring?.cancel()
            sheetHeight = destination.toInt()
            layoutSheet(followCamera)
            return
        }
        val current = sheetSpring
        if (current?.isRunning == true) {
            current.animateToFinalPosition(destination)
            return
        }
        sheetSpring =
            SpringAnimation(FloatValueHolder(sheetHeight.toFloat())).apply {
                spring = SpringForce(destination).setStiffness(360f).setDampingRatio(.86f)
                setMinimumVisibleChange(.5f)
                setStartVelocity(velocity.coerceIn(-dp(4000).toFloat(), dp(4000).toFloat()))
                addUpdateListener { _, value, _ ->
                    sheetHeight = value.toInt()
                    layoutSheet(followCamera)
                }
                start()
            }
    }

    @SuppressLint("RtlHardcoded") // Keep overlays aligned to physical hinge bounds.
    private fun layoutSheet(updateCamera: Boolean = false) {
        if (root.width == 0 || root.height == 0) return
        val plan = currentLayout()
        place(sheet, plan.sheet)
        val attributionTop =
            if (plan.mode == LayoutMode.PHONE) plan.sheet.top - dp(15)
            else plan.map.bottom - bottomInset - dp(20)
        (attribution.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            bottomMargin = 0
            topMargin = attributionTop
            leftMargin = if (plan.sidebar) plan.sheet.right + dp(12) else leftInset + dp(6)
            attribution.layoutParams = this
        }
        if (updateCamera && selectedId != null) {
            val p = plan.camera
            map?.moveCamera(
                CameraUpdateFactory.paddingTo(
                    p.left.toDouble(),
                    p.top.toDouble(),
                    p.right.toDouble(),
                    p.bottom.toDouble(),
                )
            )
        }
        positionSelectedPin()
        glass.invalidate()
    }

    private fun presentPanel(content: LinearLayout, anchor: View? = null) {
        panel?.dismiss(immediate = true)
        mapButton.active = anchor === mapButton
        panel =
            GlassPanel(this, root, glass, content, anchor, currentLayout().popupBounds) {
                panel = null
                mapButton.active = false
            }
    }

    private fun findScroll(view: View): ScrollView? {
        if (view is ScrollView) return view
        if (view is android.view.ViewGroup)
            for (i in 0 until view.childCount) findScroll(view.getChildAt(i))?.let {
                return it
            }
        return null
    }

    private fun renderSheet(change: PageChange = PageChange.NONE) {
        if (!::bodyHost.isInitialized) return
        if (renderedPage.isNotEmpty())
            scrollPositions[renderedPage] = findScroll(body)?.scrollY ?: 0
        val previous = body
        body = column()
        renderPage()
        val key = "$activeTab:${selectedId.orEmpty()}"
        renderedPage = key
        pageMotion.replace(previous, body, change)
        val scroll = findScroll(body)
        scroll?.post { scroll.scrollTo(0, scrollPositions[key] ?: 0) }
    }

    private fun renderPage() {
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
        header.addView(
            label(getString(R.string.devices), 28, Ui.ink, true),
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        header.addView(
            roundButton("↻", getString(R.string.refresh_devices)).apply {
                id = REFRESH_ID
                elevation = 0f
                setTextColor(Ui.blue)
                isEnabled = !repository.loading && !google.loading
                if (repository.loading || google.loading) NativeMotion.spin(this)
                setOnClickListener {
                    repository.refresh()
                    google.refresh()
                }
            },
            LinearLayout.LayoutParams(dp(44), dp(44)),
        )
        header.addView(
            roundButton("···", getString(R.string.account_menu)).apply {
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
                        if (repository.requiresSignIn) connect() else repository.refresh()
                    }
                }
            )
        if (google.needsSignIn)
            body.addView(
                label(getString(R.string.google_sign_in_needed), 12, Ui.blue).apply {
                    setPadding(dp(22), 0, dp(22), dp(8))
                    setOnClickListener { connectGoogle() }
                }
            )
        if (!repository.hasSession && !google.connected && locationSync.pencils.isEmpty()) {
            renderSignIn()
            return
        }
        if (visibleDevices().isEmpty()) {
            body.addView(
                label(
                        if (repository.loading || google.loading)
                            getString(R.string.loading_devices)
                        else getString(R.string.no_devices),
                        16,
                        Ui.muted,
                    )
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
                        label(
                                getString(
                                    R.string.owner_devices,
                                    member.name.ifBlank { getString(R.string.family_member) },
                                ),
                                15,
                                Ui.ink,
                                true,
                            )
                            .apply {
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
        val title = if (activeTab == 0) getString(R.string.people) else getString(R.string.items)
        val header =
            LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(22), dp(2), dp(16), dp(12))
            }
        header.addView(label(title, 28, Ui.ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(
            roundButton("↻", getString(R.string.refresh_source)).apply {
                elevation = 0f
                setTextColor(Ui.blue)
                isEnabled = !locationSync.loading
                if (locationSync.loading) NativeMotion.spin(this)
                setOnClickListener { locationSync.refresh() }
            },
            LinearLayout.LayoutParams(dp(44), dp(44)),
        )
        header.addView(
            roundButton("+", getString(R.string.add_source)).apply {
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
            val scroll = ScrollView(this).apply { isFillViewport = true }
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
                    if (locationSync.loading) getString(R.string.loading)
                    else if (activeTab == 0) getString(R.string.connect_people)
                    else getString(R.string.connect_items),
                    21,
                    Ui.ink,
                    true,
                )
            )
            space(empty, 10)
            empty.addView(
                label(
                        if (activeTab == 0) getString(R.string.people_help)
                        else getString(R.string.items_help),
                        14,
                        Ui.muted,
                    )
                    .apply { gravity = Gravity.CENTER }
            )
            space(empty, 18)
            empty.addView(
                actionButton(getString(R.string.set_up_source)).apply {
                    setOnClickListener { showSourceSettings() }
                },
                LinearLayout.LayoutParams(-1, dp(50)),
            )
            scroll.addView(empty)
            body.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
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
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content =
            column().apply {
                gravity = Gravity.CENTER
                setPadding(dp(30), dp(4), dp(30), dp(16))
            }
        content.addView(DeviceGlyph(this, "iPhone"), LinearLayout.LayoutParams(dp(52), dp(52)))
        space(content, 10)
        content.addView(
            label(getString(R.string.find_apple_devices), 22, Ui.ink, true).apply {
                gravity = Gravity.CENTER
            }
        )
        space(content, 8)
        content.addView(
            label(getString(R.string.connect_help), 14, Ui.muted).apply {
                gravity = Gravity.CENTER
            }
        )
        space(content, 16)
        content.addView(
            actionButton(getString(R.string.sign_in)).apply {
                id = OPEN_FIND_ID
                setOnClickListener { connect() }
            },
            LinearLayout.LayoutParams(-1, dp(50)),
        )
        space(content, 8)
        content.addView(
            label(getString(R.string.official_auth), 11, Ui.muted).apply {
                gravity = Gravity.CENTER
            }
        )
        space(content, 12)
        content.addView(
            actionButton(getString(R.string.google_connect), false).apply {
                id = GOOGLE_CONNECT_ID
                setOnClickListener { connectGoogle() }
            },
            LinearLayout.LayoutParams(-1, dp(50)),
        )
        scroll.addView(content)
        body.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
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
            label(device.displayName(this), 17, Ui.ink, true).apply {
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            }
        )
        labels.addView(
            label(device.subtitle(this), 12, Ui.muted).apply {
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
        row.contentDescription =
            "${device.displayName(this@MainActivity)}, ${device.statusText(this@MainActivity)}"
        NativeMotion.press(row, .98f)
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
            label(device.displayName(this), 24, Ui.ink, true),
            LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) },
        )
        header.addView(
            roundButton("×", getString(R.string.back_to_list)).apply {
                elevation = 0f
                setTextColor(Ui.muted)
                setOnClickListener {
                    closeDetails()
                }
            },
            LinearLayout.LayoutParams(dp(40), dp(40)),
        )
        content.addView(header)
        space(content, 9)
        if (device.family)
            content.addView(
                label(
                    getString(
                        R.string.owner_devices,
                        device.ownerName.ifBlank { getString(R.string.family_member) },
                    ),
                    13,
                    Ui.muted,
                )
            )
        content.addView(label(device.model, 13, Ui.muted))
        if (device.address.isNotBlank()) content.addView(label(device.address, 15))
        space(content, 5)
        content.addView(
            label(
                device.reportedStatus.ifBlank { device.statusText(this@MainActivity) } +
                    (if (device.battery != null) "   ▰ ${device.batteryText()}" else ""),
                13,
                if (device.located) Ui.green else Ui.muted,
            )
        )
        space(content, 20)
        val actions = LinearLayout(this)
        if (activeTab != 0)
            actions.addView(
                actionTile(
                        "◖))",
                        getString(R.string.play_sound),
                        getString(
                            if (device.canPlaySound) R.string.sound_action_help
                            else R.string.sound_connection_unavailable
                        ),
                        0xff6052de.toInt(),
                    )
                    .apply {
                        id = SOUND_ID
                        isEnabled = device.canPlaySound && !repository.sendingSound
                        alpha = if (isEnabled) 1f else .5f
                        contentDescription =
                            getString(
                                if (device.canPlaySound) R.string.play_sound
                                else R.string.sound_disabled_accessibility
                            )
                        setOnClickListener {
                            SoundConfirmation.show(
                                this@MainActivity,
                                device.displayName(this@MainActivity),
                            ) {
                                if (this@MainActivity.foreground)
                                    repository.playSound(device.id) { accepted ->
                                        Toast.makeText(
                                                this@MainActivity,
                                                if (accepted) R.string.sound_request_sent
                                                else R.string.sound_request_unknown,
                                                Toast.LENGTH_LONG,
                                            )
                                            .show()
                                    }
                            }
                        }
                    },
                LinearLayout.LayoutParams(0, dp(110), 1f),
            )
        if (activeTab != 0) actions.addView(View(this), LinearLayout.LayoutParams(dp(12), 1))
        actions.addView(
            actionTile("➤", getString(R.string.directions), getString(R.string.open_maps), Ui.blue)
                .apply {
                    isEnabled = device.located
                    alpha = if (device.located) 1f else .4f
                    setOnClickListener { openDirections(device) }
                },
            LinearLayout.LayoutParams(0, dp(110), 1f),
        )
        content.addView(actions)
        space(content, 20)
        content.addView(
            label(getString(R.string.refresh_location), 16, Ui.blue).apply {
                minHeight = dp(48)
                gravity = Gravity.CENTER_VERTICAL
                setOnClickListener {
                    if (
                        activeTab == 0 ||
                            activeTab == 2 ||
                            device in locationSync.pencils.map { it.asDevice() }
                    )
                        locationSync.refresh()
                    else if (device.provider == DeviceProvider.GOOGLE) google.refresh(device.id)
                    else repository.refresh()
                }
            }
        )
        val shared = sharedEntries().firstOrNull { it.id == device.id }
        content.addView(
            label(
                if (device.provider == DeviceProvider.GOOGLE) getString(R.string.google_source)
                else shared?.source ?: getString(R.string.location_only),
                11,
                Ui.muted,
            )
        )
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
        val scroll = ScrollView(this)
        val content = column().apply { setPadding(dp(24), 0, dp(24), dp(20)) }
        content.addView(label(getString(R.string.me), 28, Ui.ink, true))
        space(content, 20)
        content.addView(
            label(
                if (repository.hasSession) getString(R.string.account_connected)
                else getString(R.string.account_disconnected),
                18,
                Ui.ink,
                true,
            )
        )
        space(content, 8)
        content.addView(label(getString(R.string.android_network_unavailable), 13, Ui.muted))
        space(content, 20)
        content.addView(
            actionButton(
                    if (repository.hasSession) getString(R.string.reconnect_account)
                    else getString(R.string.sign_in)
                )
                .apply {
                    id = OPEN_FIND_ID
                    setOnClickListener { connect() }
                },
            LinearLayout.LayoutParams(-1, dp(50)),
        )
        space(content, 12)
        if (repository.hasSession)
            content.addView(
                actionButton(getString(R.string.sign_out), false).apply {
                    setOnClickListener { confirmLogout() }
                },
                LinearLayout.LayoutParams(-1, dp(48)),
            )
        space(content, 24)
        content.addView(label("Google Find Hub", 18, Ui.ink, true))
        space(content, 8)
        content.addView(
            label(
                getString(
                    if (google.connected) R.string.google_connected else R.string.google_help
                ),
                13,
                Ui.muted,
            )
        )
        space(content, 12)
        content.addView(
            actionButton(getString(R.string.google_connect), false).apply {
                id = GOOGLE_CONNECT_ID
                setOnClickListener { connectGoogle() }
            },
            LinearLayout.LayoutParams(-1, dp(50)),
        )
        if (google.connected) {
            space(content, 8)
            content.addView(
                actionButton(getString(R.string.google_disconnect), false).apply {
                    setOnClickListener { google.disconnect() }
                },
                LinearLayout.LayoutParams(-1, dp(48)),
            )
        }
        space(content, 12)
        content.addView(
            actionButton(getString(R.string.appearance_map), false).apply {
                setOnClickListener { showMapSettings() }
            },
            LinearLayout.LayoutParams(-1, dp(48)),
        )
        space(content, 10)
        content.addView(
            actionButton(getString(R.string.login_details), false).apply {
                setOnClickListener { editLogin() }
            },
            LinearLayout.LayoutParams(-1, dp(48)),
        )
        if (loginVault.paused) content.addView(label(getString(R.string.auto_paused), 12, Ui.muted))
        space(content, 10)
        content.addView(
            actionButton(getString(R.string.location_sources), false).apply {
                setOnClickListener { showSourceSettings() }
            },
            LinearLayout.LayoutParams(-1, dp(48)),
        )
        space(content, 16)
        content.addView(
            label(getString(R.string.app_version, BuildConfig.VERSION_NAME), 12, Ui.muted).apply {
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Find Bridge")
                        .setMessage(getString(R.string.about_description))
                        .setPositiveButton(getString(R.string.close), null)
                        .show()
                }
            }
        )
        scroll.addView(content)
        body.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun connectGoogle() {
        startActivityForResult(Intent(this, GoogleAuthActivity::class.java), 30)
    }

    private fun connect() {
        if (connecting) return
        if (loginVault.hasSaved && loginVault.automatic && !loginVault.paused) {
            connecting = true
            loginVault.markAttempt()
            LoginVault.clearStaged()
            repository.logout()
            startActivityForResult(
                Intent(this, AuthActivity::class.java).putExtra(AuthActivity.USE_LOGIN, true),
                10,
            )
        } else editLogin()
    }

    private fun editLogin() {
        if (connecting) return
        connecting = true
        startActivityForResult(Intent(this, LoginActivity::class.java), 10)
    }

    private fun maintainLogin() {
        if (
            !foreground ||
                connecting ||
                (!repository.requiresSignIn && repository.hasSession) ||
                !loginVault.automaticAllowed()
        )
            return
        connecting = true
        loginVault.markAttempt()
        LoginVault.clearStaged()
        startActivityForResult(
            Intent(this, AuthActivity::class.java).putExtra(AuthActivity.USE_LOGIN, true),
            10,
        )
    }

    private fun accountMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu
                .add(
                    if (repository.hasSession) getString(R.string.reconnect_account)
                    else getString(R.string.sign_in)
                )
                .setOnMenuItemClickListener {
                    connect()
                    true
                }
            if (repository.hasSession)
                menu.add(getString(R.string.sign_out)).setOnMenuItemClickListener {
                    confirmLogout()
                    true
                }
            show()
        }
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.confirm_sign_out))
            .setMessage(getString(R.string.sign_out_help))
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.sign_out)) { _, _ ->
                LoginVault.clearStaged()
                loginVault.clear()
                selectedId = null
                fitted = false
                repository.logout()
            }
            .show()
    }

    private fun selected() = visibleDevices().firstOrNull { it.id == selectedId }

    private fun select(id: String) {
        if (selectedId == id) return
        listHeight = sheetHeight
        selectedId = id
        accountTab = false
        renderSheet(PageChange.DETAIL)
        val detailHeight = minOf(dp(358).toFloat(), sheetStops()[1])
        animateSheet(detailHeight)
        updateTabs()
        renderMarkers()
        selected()?.let { focusDevice(it, detailHeight) }
    }

    private fun focusDevice(device: AppleDevice, detailHeight: Float) {
        if (!device.located) return
        val p = currentLayout(detailHeight.toInt()).camera
        val update =
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(LatLng(device.latitude!!, device.longitude!!))
                    .zoom(15.0)
                    .padding(
                        p.left.toDouble(),
                        p.top.toDouble(),
                        p.right.toDouble(),
                        p.bottom.toDouble(),
                    )
                    .build()
            )
        if (NativeMotion.reduced(this)) map?.moveCamera(update) else map?.animateCamera(update, 620)
    }

    private fun closeDetails() {
        selectedId = null
        renderSheet(PageChange.LIST)
        animateSheet((if (listHeight > 0) listHeight else dp(390)).toFloat())
        renderMarkers()
        fitted = false
        fitDevices(force = true)
    }

    private fun renderMarkers() {
        val current = map ?: return
        if (current.style?.isFullyLoaded != true) return
        val located = visibleDevices().filter { it.located }.associateBy { it.id }
        markerById.keys
            .filter { it !in located }
            .forEach { id ->
                markerById.remove(id)?.let {
                    current.removeMarker(it)
                    markers.remove(it)
                }
                markerKinds.remove(id)
            }
        located.values.forEach { device ->
            val kind = if (device.id == selectedId) "selected-transparent" else device.kind
            val icon =
                iconCache.getOrPut(kind) {
                    IconFactory.getInstance(this)
                        .fromBitmap(
                            if (kind == "selected-transparent")
                                Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
                            else DeviceGlyph.marker(this, device.kind, false)
                        )
                }
            val point = LatLng(device.latitude!!, device.longitude!!)
            val marker =
                markerById.getOrPut(device.id) {
                    current.addMarker(MarkerOptions().position(point).icon(icon)).also {
                        markers[it] = device.id
                    }
                }
            if (marker.position != point) marker.position = point
            if (markerKinds[device.id] != kind) marker.icon = icon
            markerKinds[device.id] = kind
        }
        val selected = selected()?.takeIf { it.located }
        if (selected == null) {
            selectedPin.visibility = View.INVISIBLE
            pinId = null
        } else {
            if (pinId != selected.id) {
                pinId = selected.id
                selectedPin.setImageBitmap(DeviceGlyph.marker(this, selected.kind, true))
                if (!NativeMotion.reduced(this)) {
                    selectedPin.scaleX = .78f
                    selectedPin.scaleY = .78f
                    selectedPin.alpha = 0f
                    NativeMotion.spring(selectedPin, DynamicAnimation.SCALE_X, 1f, 480f, .73f)
                    NativeMotion.spring(selectedPin, DynamicAnimation.SCALE_Y, 1f, 480f, .73f)
                    selectedPin.animate().alpha(1f).setDuration(120).start()
                }
            }
            positionSelectedPin()
        }
    }

    private fun positionSelectedPin() {
        val device = selected()?.takeIf { it.located } ?: return
        val current = map ?: return
        val point =
            current.projection.toScreenLocation(LatLng(device.latitude!!, device.longitude!!))
        selectedPin.x = point.x - dp(33)
        selectedPin.y = point.y - dp(74)
        selectedPin.visibility = View.VISIBLE
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
        val p = currentLayout().camera
        val update =
            if (points.size == 1)
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(points.first())
                        .zoom(14.0)
                        .padding(
                            p.left.toDouble(),
                            p.top.toDouble(),
                            p.right.toDouble(),
                            p.bottom.toDouble(),
                        )
                        .build()
                )
            else
                CameraUpdateFactory.newLatLngBounds(
                    LatLngBounds.Builder().includes(points).build(),
                    p.left,
                    p.top,
                    p.right,
                    p.bottom,
                )

        if (NativeMotion.reduced(this)) current.moveCamera(update)
        else current.animateCamera(update, 650)
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
            Toast.makeText(this, getString(R.string.install_map_app), Toast.LENGTH_LONG).show()
        }
    }

    override fun onChanged() {
        statusPill.text =
            if (repository.loading || google.loading || locationSync.loading)
                getString(R.string.updating)
            else if (repository.hasSession || google.connected || locationSync.configured)
                getString(R.string.find_title)
            else "Find Bridge"
        if (pageMotion.running) pendingRender = true else renderSheet()
        renderMarkers()
        fitDevices()
        maintainLogin()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 30 && resultCode == RESULT_OK) {
            data?.getStringExtra(GoogleAuthActivity.SNAPSHOT)?.let { google.accept(it) }
            fitted = false
        }
        if (requestCode == 10) {
            connecting = false
            if (resultCode == RESULT_OK) {
                fitted = false
                repository.refresh()
            }
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
        if (panel != null) {
            panel?.dismiss()
            return
        }
        if (selectedId != null) closeDetails() else if (activeTab != 1) switchTab(1) else finish()
    }

    @SuppressLint("GestureBackNavigation") override fun onBackPressed() = back()

    override fun onStart() {
        super.onStart()
        mapView.onStart()
        frameRate.start()
        windowAdapter =
            WindowInfoTrackerCallbackAdapter(WindowInfoTracker.getOrCreate(this)).also {
                it.addWindowLayoutInfoListener(this, mainExecutor, foldListener)
            }
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        google.setForeground(true)
        mapView.onResume()
        onChanged()
        repository.refresh()
        locationSync.refresh()
        handler.postDelayed(periodicRefresh, 30_000)
    }

    override fun onPause() {
        glass.stopTracking()
        frameRate.setMoving(false)
        foreground = false
        google.setForeground(false)
        handler.removeCallbacks(periodicRefresh)
        sheetSpring?.cancel()
        mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        frameRate.stop()
        windowAdapter?.removeWindowLayoutInfoListener(foldListener)
        windowAdapter = null
        mapView.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        repository.removeListener(this)
        google.removeListener(this)
        locationSync.removeListener(this)
        panel?.dismiss(immediate = true)
        pageMotion.finish()
        NativeMotion.cancelTree(root)
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
        outState.putInt("sheetHeight", sheetHeight)
        outState.putInt("listHeight", listHeight)
        outState.putBundle(
            "scrollPositions",
            Bundle().apply { scrollPositions.forEach { (key, value) -> putInt(key, value) } },
        )
        map?.cameraPosition?.let { position ->
            position.target?.let { point ->
                outState.putDoubleArray(
                    "mapCamera",
                    doubleArrayOf(
                        point.latitude,
                        point.longitude,
                        position.zoom,
                        position.bearing,
                        position.tilt,
                    ),
                )
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        root.post {
            layoutOverlays()
            selected()?.let { focusDevice(it, sheetHeight.toFloat()) }
        }
    }

    companion object {
        const val OPEN_FIND_ID = 1001
        const val MAP_ID = 1003
        const val REFRESH_ID = 1004
        const val SHEET_ID = 1005
        const val SOUND_ID = 1006
        const val SHEET_HANDLE_ID = 1007
        const val TAB_SELECTION_ID = 1008
        const val MAP_OPTIONS_ID = 1009
        const val RECENTER_ID = 1010
        const val PANEL_ID = 1011
        const val SELECTED_PIN_ID = 1012
        const val GOOGLE_CONNECT_ID = 1013
    }
}
