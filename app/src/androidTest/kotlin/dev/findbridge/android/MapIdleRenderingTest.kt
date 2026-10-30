package dev.findbridge.android

import android.graphics.Bitmap
import android.os.SystemClock
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
                                view.addOnDidFinishRenderingMapListener { fully ->
                                    if (fully) rendered.countDown()
                                }
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
                    assertTrue(
                        "Map tiles must finish loading",
                        rendered.await(30, TimeUnit.SECONDS),
                    )
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
}
