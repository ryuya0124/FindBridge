package dev.findbridge.android

import android.graphics.Bitmap
import android.graphics.PointF
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView

/** Measures real map renderer work; only counters are printed, never coordinates or device data. */
@RunWith(AndroidJUnit4::class)
class MapIdleRenderingTest {
    @Test
    fun standardAndAerialMapsStopTakingBackdropSnapshotsWhenStill() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val settings = AppearanceSettings(instrumentation.targetContext)
        val previous = settings.mapMode
        val previousAppearance = settings.appearance
        val darkQa = InstrumentationRegistry.getArguments().getString("darkqa") == "true"
        if (darkQa) settings.appearance = Appearance.DARK
        var extraMapFrames = 0
        try {
            for (mode in listOf(MapMode.STANDARD, MapMode.AERIAL)) {
                settings.mapMode = mode
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    val rendered = CountDownLatch(1)
                    val frames = AtomicInteger()
                    var initialized = false
                    val deadline = SystemClock.uptimeMillis() + 20000
                    lateinit var scene: GlassScene
                    lateinit var view: MapView
                    while (!initialized && SystemClock.uptimeMillis() < deadline) {
                        scenario.onActivity { activity ->
                            val map =
                                MainActivity::class
                                    .java
                                    .getDeclaredField("map")
                                    .apply { isAccessible = true }
                                    .get(activity) as? MapLibreMap
                            if (map?.style?.isFullyLoaded == true) {
                                fun find(root: View): View? {
                                    if (
                                        root.contentDescription ==
                                            activity.getString(R.string.items)
                                    )
                                        return root
                                    if (root is ViewGroup)
                                        for (i in 0 until root.childCount) find(root.getChildAt(i))
                                            ?.let {
                                                return it
                                            }
                                    return null
                                }
                                find(activity.window.decorView)!!.performClick()
                                view = activity.findViewById(MainActivity.MAP_ID)
                                scene =
                                    MainActivity::class
                                        .java
                                        .getDeclaredField("glass")
                                        .apply { isAccessible = true }
                                        .get(activity) as GlassScene
                                // Map completion is a load-lifecycle event and may have fired
                                // before the style callback. Observe the completed viewport's
                                // actual frames instead, including an already-cached style.
                                view.addOnDidFinishRenderingFrameListener(
                                    MapView.OnDidFinishRenderingFrameListener { fully, _, _ ->
                                        if (fully) rendered.countDown()
                                    }
                                )
                                view.addOnWillStartRenderingFrameListener {
                                    frames.incrementAndGet()
                                }
                                map.moveCamera(
                                    CameraUpdateFactory.newLatLngZoom(LatLng(35.68, 139.76), 14.0)
                                )
                                initialized = true
                            }
                        }
                        SystemClock.sleep(100)
                    }
                    assertTrue("Map style must load for rendering measurement", initialized)
                    val tilesReady = rendered.await(30, TimeUnit.SECONDS)
                    var rendererState = ""
                    scenario.onActivity { activity ->
                        rendererState =
                            "mode=$mode frames=${frames.get()} shown=${view.isShown} focus=${activity.hasWindowFocus()} size=${view.width}x${view.height}"
                    }
                    assertTrue("Map tiles must finish loading: $rendererState", tilesReady)
                    SystemClock.sleep(3000)
                    val timestamp =
                        GlassScene::class.java.getDeclaredField("lastSnapshot").apply {
                            isAccessible = true
                        }
                    var last = 0L
                    instrumentation.runOnMainSync { last = timestamp.getLong(scene) }
                    frames.set(0)
                    var captures = 0
                    repeat(60) {
                        SystemClock.sleep(100)
                        instrumentation.runOnMainSync {
                            val next = timestamp.getLong(scene)
                            if (next != last) {
                                captures++
                                last = next
                            }
                        }
                    }
                    println(
                        "$mode idle 6s: backdropSnapshots=$captures mapFrames=${frames.get()} refreshMode=${view.renderingRefreshMode}"
                    )
                    assertEquals(
                        "A still map must not repeatedly read GPU pixels for glass",
                        0,
                        captures,
                    )
                    frames.set(0)
                    scenario.onActivity { activity ->
                        val currentMap =
                            MainActivity::class
                                .java
                                .getDeclaredField("map")
                                .apply { isAccessible = true }
                                .get(activity) as MapLibreMap
                        scene.capture(currentMap, true)
                    }
                    SystemClock.sleep(1500)
                    println("$mode single backdrop copy: extraMapFrames=${frames.get()}")
                    extraMapFrames += frames.get()
                    instrumentation.runOnMainSync {
                        assertEquals(
                            "The GPU surface copy must use the glass resolution",
                            minOf(view.width, 640),
                            scene.bitmap?.width,
                        )
                        val colors = mutableSetOf<Int>()
                        scene.bitmap!!.let { image ->
                            for (y in 0 until image.height step 32) for (x in
                                0 until image.width step 32) colors += image.getPixel(x, y)
                        }
                        assertTrue(
                            "The copied backdrop must contain rendered map pixels",
                            colors.size > 16,
                        )
                    }
                    if (darkQa && mode == MapMode.AERIAL) {
                        val screenshot = instrumentation.uiAutomation.takeScreenshot()
                        java.io
                            .File(instrumentation.targetContext.cacheDir, "dark-aerial-tabs.png")
                            .outputStream()
                            .use {
                                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                            }
                        screenshot.recycle()
                    }
                    val aligned = CountDownLatch(1)
                    var alignmentFailure: Throwable? = null
                    scenario.onActivity { activity ->
                        val currentMap =
                            MainActivity::class
                                .java
                                .getDeclaredField("map")
                                .apply {
                                    isAccessible = true
                                }
                                .get(activity) as MapLibreMap
                        val points =
                            floatArrayOf(
                                view.width * .37f,
                                view.height * .44f,
                                view.width * .63f,
                                view.height * .58f,
                            )
                        val geography =
                            (0..1).map {
                                currentMap.projection.fromScreenLocation(
                                    PointF(points[it * 2], points[it * 2 + 1])
                                )
                            }
                        // Model a readback/blur already in flight. The visible old pixels
                        // still have to align before any replacement bitmap can arrive.
                        val pending =
                            GlassScene::class.java.getDeclaredField("pending").apply {
                                isAccessible = true
                            }
                        val before = scene.bitmap
                        pending.setBoolean(scene, true)
                        currentMap.moveCamera(
                            CameraUpdateFactory.newCameraPosition(
                                CameraPosition.Builder(currentMap.cameraPosition)
                                    .target(LatLng(35.681, 139.763))
                                    .zoom(14.2)
                                    .bearing(23.0)
                                    .tilt(25.0)
                                    .build()
                            )
                        )
                        view.postOnAnimation {
                            view.postOnAnimation {
                                try {
                                    assertSame(
                                        "Reprojection must not wait for another bitmap",
                                        before,
                                        scene.bitmap,
                                    )
                                    scene.viewportTransform.mapPoints(points)
                                    geography.forEachIndexed { index, coordinate ->
                                        val expected =
                                            currentMap.projection.toScreenLocation(coordinate)
                                        assertEquals(
                                            "Backdrop x must match the real map projection",
                                            expected.x,
                                            points[index * 2],
                                            3f,
                                        )
                                        assertEquals(
                                            "Backdrop y must match the real map projection",
                                            expected.y,
                                            points[index * 2 + 1],
                                            3f,
                                        )
                                    }
                                } catch (failure: Throwable) {
                                    alignmentFailure = failure
                                } finally {
                                    pending.setBoolean(scene, false)
                                    aligned.countDown()
                                }
                            }
                        }
                    }
                    assertTrue(
                        "The next displayed frames must align the backdrop",
                        aligned.await(5, TimeUnit.SECONDS),
                    )
                    alignmentFailure?.let { throw it }
                    measureMovingCadence(scenario, view, scene, mode)
                }
            }
            assertEquals(
                "Copying an already-rendered backdrop must not render the map again",
                0,
                extraMapFrames,
            )
        } finally {
            settings.mapMode = previous
            settings.appearance = previousAppearance
        }
    }

    private fun measureMovingCadence(
        scenario: ActivityScenario<MainActivity>,
        view: MapView,
        scene: GlassScene,
        mode: MapMode,
    ) {
        val finished = CountDownLatch(1)
        val ticks = mutableListOf<Long>()
        val mapFrames = AtomicInteger()
        var moving = true
        var requestedHz = 0f
        var previousBackdrop: Bitmap? = null
        var backdropUpdates = 0
        var failure: Throwable? = null
        scenario.onActivity { activity ->
            val map =
                MainActivity::class
                    .java
                    .getDeclaredField("map")
                    .apply {
                        isAccessible = true
                    }
                    .get(activity) as MapLibreMap
            view.addOnWillStartRenderingFrameListener { if (moving) mapFrames.incrementAndGet() }
            val callback =
                object : Choreographer.FrameCallback {
                    override fun doFrame(frameTimeNanos: Long) {
                        if (!moving) return
                        requestedHz =
                            maxOf(requestedHz, activity.window.attributes.preferredRefreshRate)
                        ticks += frameTimeNanos
                        if (scene.bitmap !== previousBackdrop) {
                            previousBackdrop = scene.bitmap
                            backdropUpdates++
                        }
                        Choreographer.getInstance().postFrameCallback(this)
                    }
                }
            Choreographer.getInstance().postFrameCallback(callback)
            fun ended() {
                moving = false
                Choreographer.getInstance().removeFrameCallback(callback)
                view.postOnAnimation {
                    try {
                        assertEquals(
                            "Idle releases the high-refresh vote",
                            0f,
                            activity.window.attributes.preferredRefreshRate,
                            0f,
                        )
                    } catch (problem: Throwable) {
                        failure = problem
                    }
                    finished.countDown()
                }
            }
            map.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder(map.cameraPosition)
                        .target(LatLng(35.682, 139.764))
                        .zoom(14.3)
                        .bearing(40.0)
                        .tilt(20.0)
                        .build()
                ),
                1200,
                object : MapLibreMap.CancelableCallback {
                    override fun onFinish() = ended()

                    override fun onCancel() = ended()
                },
            )
        }
        assertTrue(
            "Map motion must finish without a render backlog",
            finished.await(8, TimeUnit.SECONDS),
        )
        failure?.let { throw it }
        assertTrue("Map movement requests the display's high cadence", requestedHz >= 60f)
        assertTrue("Vsync must drive the moving backdrop", ticks.size >= 5)
        val intervals = ticks.zipWithNext { a, b -> (b - a) / 1_000_000.0 }.sorted()
        println(
            "$mode moving: requestedHz=$requestedHz vsyncMedianMs=${intervals[intervals.size / 2]} callbacks=${ticks.size} mapFrames=${mapFrames.get()} backdropUpdates=$backdropUpdates"
        )
    }
}
