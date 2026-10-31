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
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.RelativeLayout
import android.widget.ScrollView
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
import dev.findbridge.android.Ui.materialSwitch
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
    private var googleCommandOpening = false

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    private lateinit var repository: AppleRepository
    private lateinit var google: GoogleRepository
    private lateinit var loginVault: LoginVault
    private var foreground = false
    private lateinit var appleConnection: AppleConnectionController
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
    private var displaySettings: DisplaySettings? = null
    private var displaySettingsOpen = false
    private lateinit var attribution: TextView
    private lateinit var statusPill: TextView
    private val markers = mutableMapOf<Marker, String>()
    private val markerById = mutableMapOf<String, Marker>()
    private val markerKinds = mutableMapOf<String, String>()
    private val markerGroups = mutableMapOf<String, List<String>>()
    private val iconCache = mutableMapOf<String, Icon>()
    private val clusterIconCache =
        object : android.util.LruCache<String, Icon>(4 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Icon) = value.bitmap.allocationByteCount
        }
    private lateinit var selectedPin: ImageView
    private var pinId: String? = null
    private var pinImageKey: String? = null
    private val deviceImageListener: () -> Unit = { renderMarkers() }
    private var selectedId: String? = null
    private var accountTab = false
    private var activeTab = 1
    private var fitted = false
    private var mapStyleGeneration = 0
    private var mapStyleRequested = false
    private var rememberOverviewZoom = false
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
        appleConnection = AppleConnectionController(this, repository, loginVault) { foreground }
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
            value.addOnCameraMoveStartedListener { reason ->
                if (
                    reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE &&
                        selectedId == null
                )
                    rememberOverviewZoom = true
                frameRate.setMoving(true)
                glass.startTracking(value)
            }
            value.addOnCameraMoveListener {
                positionSelectedPin()
            }
            value.addOnCameraIdleListener {
                if (rememberOverviewZoom) {
                    rememberOverviewZoom = false
                    appearance.overviewZoom = value.cameraPosition.zoom
                }
                glass.stopTracking()
                glass.trackCamera(value)
                frameRate.setMoving(false)
                renderMarkers()
                glass.capture(value, immediate = true)
            }
            mapView.addOnDidBecomeIdleListener { glass.capture(value, immediate = true) }
            value.setOnMarkerClickListener { marker ->
                markers[marker]?.let { id ->
                    val members = markerGroups[id]
                    if (members != null) showMarkerGroup(members) else select(id)
                }
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
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val lightBars =
                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(
                if (Ui.dark) 0 else lightBars,
                lightBars,
            )
        }
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
        if (savedInstanceState?.getBoolean("displaySettingsOpen") == true) {
            root.addOnLayoutChangeListener(
                object : View.OnLayoutChangeListener {
                    override fun onLayoutChange(
                        v: View,
                        l: Int,
                        t: Int,
                        r: Int,
                        b: Int,
                        ol: Int,
                        ot: Int,
                        or: Int,
                        ob: Int,
                    ) {
                        if (r > l && b > t) {
                            root.removeOnLayoutChangeListener(this)
                            root.post { if (!isFinishing && !isDestroyed) showMapSettings() }
                        }
                    }
                }
            )
        }
        if (savedInstanceState == null)
            root.post {
                NativeMotion.enter(sheet, dp(30).toFloat())
                NativeMotion.enter(tabs, dp(22).toFloat(), 40)
                NativeMotion.enter(controls, dp(-10).toFloat(), 65)
            }
    }

    private fun roundButton(glyph: String, description: String) =
        RefreshButton(this).apply {
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

    private fun headerButton(symbol: RefreshButton.Symbol, description: String) =
        roundButton("", description).apply {
            this.symbol = symbol
            elevation = 0f
            setTextColor(Ui.blue)
            background = Ui.ripple(Color.TRANSPARENT, dp(18))
        }

    private fun headerActions(first: RefreshButton, second: RefreshButton): FrameLayout =
        FrameLayout(this).apply {
            addView(
                View(this@MainActivity).apply {
                    background = GlassDrawable(glass, this, dp(18).toFloat(), .18f)
                },
                FrameLayout.LayoutParams(-1, dp(36), Gravity.CENTER),
            )
            val buttons = LinearLayout(this@MainActivity)
            buttons.addView(first, LinearLayout.LayoutParams(dp(44), dp(44)))
            buttons.addView(second, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(buttons, FrameLayout.LayoutParams(-1, -1))
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
                id = R.id.find_title
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
        val preserveCamera = mapStyleRequested
        mapStyleRequested = true
        val generation = ++mapStyleGeneration
        mapButton.symbol =
            if (appearance.mapMode == MapMode.STANDARD) MapControlButton.Symbol.MAP
            else MapControlButton.Symbol.SATELLITE
        mapButton.invalidate()
        current.setStyle(
            Style.Builder().fromJson(MapStyles.create(this, appearance.mapMode, Ui.dark))
        ) {
            if (generation != mapStyleGeneration) return@setStyle
            current.clear()
            markers.clear()
            markerById.clear()
            markerKinds.clear()
            renderMarkers()
            if (restoredCamera != null) {
                restoredCamera = null
                fitted = true
                layoutSheet(updateCamera = true)
            } else if (!preserveCamera) {
                if (selectedId != null) selected()?.let { focusDevice(it, sheetHeight.toFloat()) }
                else fitDevices()
            }
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
        displaySettings?.dismissChoices()
        displaySettings =
            DisplaySettings(
                    this,
                    appearance,
                    glass,
                    choiceBounds = {
                        val bounds = currentLayout().popupBounds
                        val origin = IntArray(2)
                        root.getLocationOnScreen(origin)
                        android.graphics.Rect(
                            bounds.left + origin[0],
                            bounds.top + origin[1],
                            bounds.right + origin[0],
                            bounds.bottom + origin[1],
                        )
                    },
                    onMapChanged = ::applyMapStyle,
                    present = {
                        presentPanel(it, mapButton)
                        displaySettingsOpen = true
                    },
                )
                .also { it.show() }
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
        displaySettings?.dismissChoices()
        displaySettingsOpen = false
        panel?.dismiss(immediate = true)
        mapButton.active = anchor === mapButton
        panel =
            GlassPanel(this, root, glass, content, anchor, currentLayout().popupBounds) {
                panel = null
                displaySettingsOpen = false
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
        val refresh =
            headerButton(RefreshButton.Symbol.REFRESH, getString(R.string.refresh_devices)).apply {
                id = REFRESH_ID
                elevation = 0f
                setTextColor(Ui.blue)
                isEnabled = !repository.loading && !google.loading
                refreshing = repository.loading || google.loading
                setOnClickListener {
                    repository.refresh()
                    google.refresh()
                }
            }
        val more =
            headerButton(RefreshButton.Symbol.MORE, getString(R.string.account_menu)).apply {
                elevation = 0f
                setTextColor(Ui.blue)
                setOnClickListener(::accountMenu)
            }
        header.addView(headerActions(refresh, more), LinearLayout.LayoutParams(dp(88), dp(44)))
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
        val refresh =
            headerButton(RefreshButton.Symbol.REFRESH, getString(R.string.refresh_source)).apply {
                elevation = 0f
                setTextColor(Ui.blue)
                isEnabled = !locationSync.loading
                refreshing = locationSync.loading
                setOnClickListener { locationSync.refresh() }
            }
        val add =
            headerButton(RefreshButton.Symbol.ADD, getString(R.string.add_source)).apply {
                elevation = 0f
                setTextColor(Ui.blue)
                setOnClickListener { showSourceSettings() }
            }
        header.addView(headerActions(refresh, add), LinearLayout.LayoutParams(dp(88), dp(44)))
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
            actionButton(
                    getString(
                        if (google.signedIn && !google.connected) R.string.google_findhub_connect
                        else R.string.google_connect
                    ),
                    false,
                )
                .apply {
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
        row.addView(DeviceGlyph(this, device), LinearLayout.LayoutParams(dp(48), dp(52)))
        val labels = RelativeLayout(this)
        val nameId = View.generateViewId()
        val batteryId = View.generateViewId()
        labels.addView(
            label(device.displayName(this), 17, Ui.ink, true).apply {
                id = nameId
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            },
            RelativeLayout.LayoutParams(-1, -2).apply {
                if (device.battery != null) addRule(RelativeLayout.START_OF, batteryId)
            },
        )
        if (device.battery != null)
            labels.addView(
                label(
                        device.batteryText(),
                        11,
                        if (device.battery < .2) 0xffff453a.toInt() else Ui.muted,
                    )
                    .apply {
                        id = batteryId
                        setPadding(dp(10), 0, 0, 0)
                    },
                RelativeLayout.LayoutParams(-2, -2).apply {
                    addRule(RelativeLayout.ALIGN_PARENT_END)
                    addRule(RelativeLayout.ALIGN_BASELINE, nameId)
                },
            )
        labels.addView(
            label(device.subtitle(this), 12, Ui.muted).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
            RelativeLayout.LayoutParams(-1, -2).apply { addRule(RelativeLayout.BELOW, nameId) },
        )
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(15) })
        row.addView(
            SettingsAccessory(this).apply {
                id = R.id.device_disclosure
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LinearLayout.LayoutParams(dp(16), dp(48)).apply { leftMargin = dp(10) },
        )
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
        header.addView(DeviceGlyph(this, device), LinearLayout.LayoutParams(dp(40), dp(45)))
        header.addView(
            label(device.displayName(this), 24, Ui.ink, true),
            LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) },
        )
        header.addView(
            roundButton("", getString(R.string.back_to_list)).apply {
                symbol = RefreshButton.Symbol.CLOSE
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
                if (device.located) Ui.positiveText else Ui.muted,
            )
        )
        space(content, 20)
        val actions = LinearLayout(this)
        if (activeTab != 0)
            actions.addView(
                actionTile(
                        "◖))",
                        getString(
                            if (device.provider == DeviceProvider.GOOGLE && device.soundPlaying)
                                R.string.stop_sound
                            else R.string.play_sound
                        ),
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
                                if (device.provider == DeviceProvider.GOOGLE && device.soundPlaying)
                                    R.string.stop_sound
                                else if (device.canPlaySound) R.string.play_sound
                                else R.string.sound_disabled_accessibility
                            )
                        setOnClickListener {
                            SoundConfirmation.show(
                                this@MainActivity,
                                device.displayName(this@MainActivity),
                                stop =
                                    device.provider == DeviceProvider.GOOGLE && device.soundPlaying,
                                google = device.provider == DeviceProvider.GOOGLE,
                            ) {
                                if (
                                    this@MainActivity.foreground &&
                                        device.provider == DeviceProvider.GOOGLE
                                )
                                    googleCommand(
                                        device,
                                        if (device.soundPlaying) GoogleCommandType.STOP_SOUND
                                        else GoogleCommandType.RING,
                                    )
                                else if (this@MainActivity.foreground)
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
        if (
            device.provider in setOf(DeviceProvider.APPLE, DeviceProvider.GOOGLE) &&
                (device.lostModeSupported || device.lostModeState != LostModeState.OFF)
        ) {
            val stateText =
                getString(
                    when (device.lostModeState) {
                        LostModeState.OFF -> R.string.lost_activate
                        LostModeState.PENDING -> R.string.lost_pending
                        LostModeState.ENABLED -> R.string.lost_enabled
                    }
                )
            content.addView(
                actionButton(stateText, false).apply {
                    id = LOST_MODE_ID
                    isEnabled =
                        device.lostModeState == LostModeState.OFF && !repository.sendingLostMode
                    setOnClickListener { anchor ->
                        presentPanel(
                            LostModeForm(
                                this@MainActivity,
                                device,
                                canceled = { panel?.dismiss() },
                                confirmed = confirmed@{ draft ->
                                        if (
                                            !this@MainActivity.foreground ||
                                                !this@MainActivity.hasWindowFocus()
                                        )
                                            return@confirmed
                                        panel?.dismiss()
                                        if (device.provider == DeviceProvider.GOOGLE) {
                                            googleCommand(
                                                device,
                                                GoogleCommandType.MARK_LOST,
                                                draft,
                                            )
                                            return@confirmed
                                        }
                                        repository.markLost(device.id, draft) { result ->
                                            if (
                                                this@MainActivity.foreground &&
                                                    !this@MainActivity.isFinishing
                                            )
                                                Toast.makeText(
                                                        this@MainActivity,
                                                        when (result) {
                                                            LostModeResult.REQUESTED ->
                                                                R.string.lost_requested
                                                            LostModeResult.AUTH_REQUIRED ->
                                                                R.string.sign_in_required
                                                            LostModeResult.REJECTED ->
                                                                R.string.lost_failed
                                                            LostModeResult.UNKNOWN ->
                                                                R.string.lost_unknown
                                                        },
                                                        Toast.LENGTH_LONG,
                                                    )
                                                    .show()
                                        }
                                    },
                            ),
                            anchor,
                        )
                    }
                },
                LinearLayout.LayoutParams(-1, dp(52)),
            )
            space(content, 16)
        }
        val information = device.metadata.rows().toMutableList()
        if (device.provider == DeviceProvider.APPLE && device.notifyWhenFoundSupported) {
            content.addView(
                materialSwitch().apply {
                    text = getString(R.string.notify_found)
                    isChecked =
                        if (repository.notificationTarget == device.id) repository.notificationValue
                        else device.notifyWhenFound
                    isEnabled = repository.notificationTarget == null
                    setOnCheckedChangeListener { _, checked ->
                        repository.setNotifyWhenFound(device.id, checked) { result ->
                            Toast.makeText(
                                    this@MainActivity,
                                    when (result) {
                                        AppleNotification.Result.SAVED -> R.string.notify_saved
                                        AppleNotification.Result.AUTH_REQUIRED ->
                                            R.string.sign_in_required
                                        else -> R.string.notify_unknown
                                    },
                                    Toast.LENGTH_LONG,
                                )
                                .show()
                        }
                    }
                },
                LinearLayout.LayoutParams(-1, dp(52)),
            )
            content.addView(label(getString(R.string.notify_found_help), 12, Ui.muted))
            space(content, 16)
        }
        if (device.provider == DeviceProvider.GOOGLE) {
            content.addView(
                actionButton(getString(R.string.google_verify_location), false).apply {
                    id = GOOGLE_VERIFY_ID
                    setOnClickListener {
                        val index =
                            device.id.split(':').getOrNull(1)?.toIntOrNull()
                                ?: return@setOnClickListener
                        startActivityForResult(
                            Intent(this@MainActivity, GoogleWebSessionActivity::class.java)
                                .putExtra(GoogleWebSessionActivity.DEVICE_INDEX, index),
                            30,
                        )
                    }
                },
                LinearLayout.LayoutParams(-1, dp(48)),
            )
            content.addView(label(getString(R.string.google_verify_help), 12, Ui.muted))
            space(content, 16)
        }
        val batteryState =
            when (device.metadata.batteryState) {
                "charging" -> getString(R.string.battery_charging)
                "not_charging" -> getString(R.string.battery_not_charging)
                "full" -> getString(R.string.battery_full)
                else -> ""
            }
        if (batteryState.isNotBlank()) information += R.string.battery_status to batteryState
        if (information.isNotEmpty()) {
            content.addView(label(getString(R.string.device_information), 16, Ui.ink, true))
            space(content, 10)
            val card =
                column().apply {
                    setPadding(dp(14), dp(4), dp(14), dp(4))
                    background = GlassDrawable(glass, this, dp(20).toFloat(), .38f)
                }
            for ((index, item) in information.withIndex()) {
                if (index > 0)
                    card.addView(
                        View(this).apply { setBackgroundColor(Ui.separator) },
                        LinearLayout.LayoutParams(-1, dp(.5f)),
                    )
                val row =
                    LinearLayout(this).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dp(12), 0, dp(12))
                    }
                row.addView(
                    label(getString(item.first), 13, Ui.muted),
                    LinearLayout.LayoutParams(0, -2, 1f),
                )
                row.addView(
                    label(item.second, 13, Ui.ink).apply {
                        gravity = Gravity.END
                        setTextIsSelectable(true)
                        setPadding(dp(12), 0, 0, 0)
                    },
                    LinearLayout.LayoutParams(0, -2, 1.4f),
                )
                card.addView(row)
            }
            content.addView(card)
            space(content, 16)
        }
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
        space(content, 10)
        content.addView(
            actionButton(getString(R.string.login_details), false).apply {
                minimumHeight = dp(48)
                setOnClickListener { editLogin() }
            },
            LinearLayout.LayoutParams(-1, -2),
        )
        if (loginVault.paused) content.addView(label(getString(R.string.auto_paused), 12, Ui.muted))
        space(content, 24)
        content.addView(label("Google Find Hub", 18, Ui.ink, true))
        space(content, 8)
        content.addView(
            label(
                getString(
                    when {
                        google.connected -> R.string.google_connected
                        google.signedIn -> R.string.google_signed_in
                        else -> R.string.google_help
                    }
                ),
                13,
                Ui.muted,
            )
        )
        space(content, 12)
        content.addView(
            actionButton(
                    getString(
                        if (google.signedIn && !google.connected) R.string.google_findhub_connect
                        else R.string.google_connect
                    ),
                    false,
                )
                .apply {
                    id = GOOGLE_CONNECT_ID
                    setOnClickListener { connectGoogle() }
                },
            LinearLayout.LayoutParams(-1, dp(50)),
        )
        if (google.connected || google.signedIn) {
            space(content, 8)
            content.addView(
                actionButton(getString(R.string.google_disconnect), false).apply {
                    setOnClickListener { google.disconnect() }
                },
                LinearLayout.LayoutParams(-1, dp(48)),
            )
        }
        space(content, 24)
        content.addView(label(getString(R.string.app_settings), 18, Ui.ink, true))
        space(content, 12)
        content.addView(
            actionButton(getString(R.string.appearance_map), false).apply {
                setOnClickListener { showMapSettings() }
            },
            LinearLayout.LayoutParams(-1, dp(48)),
        )
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
        startActivityForResult(
            Intent(
                this,
                if (google.signedIn) GoogleWebSessionActivity::class.java
                else GoogleAuthActivity::class.java,
            ),
            30,
        )
    }

    private fun googleCommand(
        device: AppleDevice,
        type: GoogleCommandType,
        draft: LostModeDraft? = null,
    ) {
        if (googleCommandOpening) return
        val ticket = google.commandTicket(device, type, draft)
        if (ticket == null) {
            Toast.makeText(this, R.string.google_action_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        googleCommandOpening = true
        startActivityForResult(
            Intent(this, GoogleWebSessionActivity::class.java)
                .putExtra(GoogleWebSessionActivity.COMMAND_TICKET, ticket),
            31,
        )
    }

    private fun connect() = appleConnection.reconnect()

    private fun editLogin() = appleConnection.editCredentials()

    private fun maintainLogin() {
        if (::appleConnection.isInitialized) appleConnection.maintain()
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

    private fun deviceIcon(device: AppleDevice): Pair<String, Icon> {
        val key = DeviceGlyph.imageKey(device)
        iconCache[key]?.let {
            return key to it
        }
        val original = device.provider == DeviceProvider.GOOGLE || device.imageUrl.isNotEmpty()
        val ready = !original || DeviceImages.peek(device.imageUrl) != null
        if (!ready && device.imageUrl.isNotEmpty()) DeviceImages.load(device.imageUrl)
        val actualKey = if (ready) key else "$key:pending"
        return actualKey to
            iconCache.getOrPut(actualKey) {
                IconFactory.getInstance(this).fromBitmap(DeviceGlyph.marker(this, device, false))
            }
    }

    private fun showMarkerGroup(ids: List<String>) {
        val members = visibleDevices().filter { it.id in ids }
        if (members.isEmpty()) return
        val content = column().apply { setPadding(dp(8), dp(12), dp(8), dp(12)) }
        content.addView(
            label(getString(R.string.devices_here, members.size), 21, Ui.ink, true).apply {
                setPadding(dp(14), 0, dp(14), dp(12))
            }
        )
        members.forEach { device ->
            val index = content.childCount
            addDeviceRow(content, device)
            content.getChildAt(index).setOnClickListener {
                panel?.dismiss(immediate = true)
                select(device.id)
            }
        }
        presentPanel(content)
        panel?.accessibilityPaneTitle = getString(R.string.devices_here, members.size)
    }

    private fun renderMarkers() {
        val current = map ?: return
        if (current.style?.isFullyLoaded != true) return
        val located = visibleDevices().filter { it.located }.associateBy { it.id }
        val points =
            located.values
                .filter { it.id != selectedId }
                .map { device ->
                    val point =
                        current.projection.toScreenLocation(
                            LatLng(device.latitude!!, device.longitude!!)
                        )
                    MarkerPoint(device.id, point.x.toDouble(), point.y.toDouble())
                }
        val groups =
            if (activeTab == 1) MarkerGroups.collect(points, dp(64).toDouble())
            else points.map { listOf(it) }
        data class DisplayMarker(
            val id: String,
            val position: LatLng,
            val key: String,
            val icon: Icon,
        )
        val nextGroups = mutableMapOf<String, List<String>>()
        val display =
            groups
                .map { group ->
                    val members = group.mapNotNull { located[it.id] }
                    if (members.size == 1) {
                        val device = members.first()
                        val (key, icon) = deviceIcon(device)
                        DisplayMarker(
                            device.id,
                            LatLng(device.latitude!!, device.longitude!!),
                            key,
                            icon,
                        )
                    } else {
                        val ids = members.map { it.id }.sorted()
                        val digest =
                            java.security.MessageDigest.getInstance("SHA-256")
                                .digest(ids.joinToString("\u0000").toByteArray())
                                .take(12)
                                .joinToString("") { "%02x".format(it) }
                        val id = "cluster:$digest"
                        nextGroups[id] = ids
                        val first = members.first()
                        val second =
                            members.firstOrNull { it.provider != first.provider }
                                ?: members.drop(1).first()
                        val icons = listOf(first, second).map(::deviceIcon)
                        val key = id + icons.joinToString { it.first }
                        val icon =
                            clusterIconCache.get(key)
                                ?: IconFactory.getInstance(this)
                                    .fromBitmap(
                                        DeviceGlyph.cluster(
                                            this,
                                            icons.map { it.second.bitmap },
                                            members.size,
                                        )
                                    )
                                    .also { clusterIconCache.put(key, it) }
                        val point =
                            android.graphics.PointF(
                                group.map { it.x }.average().toFloat(),
                                group.map { it.y }.average().toFloat(),
                            )
                        DisplayMarker(id, current.projection.fromScreenLocation(point), key, icon)
                    }
                }
                .toMutableList()
        located[selectedId]?.let { device ->
            val key = "selected-transparent"
            val icon =
                iconCache.getOrPut(key) {
                    IconFactory.getInstance(this)
                        .fromBitmap(Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888))
                }
            display +=
                DisplayMarker(device.id, LatLng(device.latitude!!, device.longitude!!), key, icon)
        }
        val ids = display.map { it.id }.toSet()
        markerById.keys
            .filter { it !in ids }
            .forEach { id ->
                markerById.remove(id)?.let {
                    current.removeMarker(it)
                    markers.remove(it)
                }
                markerKinds.remove(id)
            }
        markerGroups.clear()
        markerGroups.putAll(nextGroups)
        display.forEach { entry ->
            val marker =
                markerById.getOrPut(entry.id) {
                    current
                        .addMarker(MarkerOptions().position(entry.position).icon(entry.icon))
                        .also { markers[it] = entry.id }
                }
            if (marker.position != entry.position) marker.position = entry.position
            if (markerKinds[entry.id] != entry.key) marker.icon = entry.icon
            markerKinds[entry.id] = entry.key
        }
        val selected = selected()?.takeIf { it.located }
        if (selected == null) {
            selectedPin.visibility = View.INVISIBLE
            pinId = null
            pinImageKey = null
        } else {
            val key = DeviceGlyph.imageKey(selected)
            val original =
                selected.provider == DeviceProvider.GOOGLE || selected.imageUrl.isNotEmpty()
            val ready = !original || DeviceImages.peek(selected.imageUrl) != null
            val actualKey = if (ready) key else "$key:pending"
            val alreadyDrawn = pinId == selected.id && pinImageKey == key
            if (!alreadyDrawn && (pinId != selected.id || pinImageKey != actualKey)) {
                val changedSelection = pinId != selected.id
                pinId = selected.id
                pinImageKey = actualKey
                selectedPin.setImageBitmap(DeviceGlyph.marker(this, selected, true))
                if (changedSelection && !NativeMotion.reduced(this)) {
                    selectedPin.scaleX = .78f
                    selectedPin.scaleY = .78f
                    selectedPin.alpha = 0f
                    NativeMotion.spring(selectedPin, DynamicAnimation.SCALE_X, 1f, 480f, .73f)
                    NativeMotion.spring(selectedPin, DynamicAnimation.SCALE_Y, 1f, 480f, .73f)
                    selectedPin.animate().alpha(1f).setDuration(120).start()
                }
            }
            if (!alreadyDrawn && !ready && selected.imageUrl.isNotEmpty())
                DeviceImages.load(selected.imageUrl)
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
                        .zoom(appearance.overviewZoom)
                        .padding(
                            p.left.toDouble(),
                            p.top.toDouble(),
                            p.right.toDouble(),
                            p.bottom.toDouble(),
                        )
                        .build()
                )
            else {
                val bounds =
                    CameraUpdateFactory.newLatLngBounds(
                        LatLngBounds.Builder().includes(points).build(),
                        p.left,
                        p.top,
                        p.right,
                        p.bottom,
                    )
                val camera = bounds.getCameraPosition(current)
                if (camera != null && camera.zoom > appearance.overviewZoom)
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder(camera).zoom(appearance.overviewZoom).build()
                    )
                else bounds
            }

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
        if (pageMotion.running) pendingRender = true else renderSheet()
        renderMarkers()
        fitDevices()
        maintainLogin()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 31) {
            googleCommandOpening = false
            val result = data?.getStringExtra(GoogleWebSessionActivity.COMMAND_RESULT)
            if (result != null && result != "CANCELED")
                Toast.makeText(
                        this,
                        when (result) {
                            "REQUESTED" -> R.string.google_action_requested
                            "UNAVAILABLE" -> R.string.google_action_unavailable
                            "ACCOUNT_MISMATCH",
                            "TARGET_CHANGED" -> R.string.google_action_target_changed
                            else -> R.string.google_action_unknown
                        },
                        Toast.LENGTH_LONG,
                    )
                    .show()
            google.invalidateRead()
        }
        if (requestCode == 30 && resultCode == RESULT_OK) {
            data?.getStringExtra(GoogleAuthActivity.SNAPSHOT)?.let { google.accept(it) }
            google.invalidateRead()
            fitted = false
        }
        if (requestCode == 10) {
            appleConnection.completed()
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
        DeviceImages.addListener(deviceImageListener)
        windowAdapter =
            WindowInfoTrackerCallbackAdapter(WindowInfoTracker.getOrCreate(this)).also {
                it.addWindowLayoutInfoListener(this, mainExecutor, foldListener)
            }
    }

    override fun onResume() {
        super.onResume()
        glass.setActive(true)
        foreground = true
        google.setForeground(true)
        mapView.onResume()
        onChanged()
        repository.refresh()
        locationSync.refresh()
        handler.postDelayed(periodicRefresh, 30_000)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::loginVault.isInitialized) maintainLogin()
    }

    override fun onPause() {
        glass.setActive(false)
        frameRate.setMoving(false)
        foreground = false
        google.setForeground(false)
        handler.removeCallbacks(periodicRefresh)
        sheetSpring?.cancel()
        mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        DeviceImages.removeListener(deviceImageListener)
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
        displaySettings?.dismissChoices()
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
        outState.putBoolean("displaySettingsOpen", displaySettingsOpen)
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
        const val LOST_MODE_ID = 0x16fb0130
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
        const val GOOGLE_VERIFY_ID = 1014
    }
}
