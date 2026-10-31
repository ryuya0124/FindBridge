package dev.findbridge.android

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/** Real map texture -> shared GPU blur -> refractive glass -> window pixels, without readback. */
class LiveGlassRenderingTest {
    @Test
    fun mapColorChangesReachGlassWhileCpuReadbackIsBlocked() {
        assumeTrue(android.os.Build.VERSION.SDK_INT >= 33)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(!AppleRepository.get(context).hasSession && !LoginVault(context).hasSaved)
        val settings = AppearanceSettings(context)
        val previous = settings.appearance
        settings.appearance = Appearance.DARK
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var scene: GlassScene
                lateinit var panel: View
                lateinit var mapView: MapView
                lateinit var map: MapLibreMap
                val ready = CountDownLatch(1)
                scenario.onActivity { activity ->
                    scene =
                        MainActivity::class
                            .java
                            .getDeclaredField("glass")
                            .apply { isAccessible = true }
                            .get(activity) as GlassScene
                    scene.intensity = 1f
                    // Neither an unfinished legacy readback nor its bitmap can gate this path.
                    GlassScene::class
                        .java
                        .getDeclaredField("pending")
                        .apply { isAccessible = true }
                        .setBoolean(scene, true)
                    mapView = activity.findViewById(MainActivity.MAP_ID)
                    panel =
                        View(activity).apply {
                            background = GlassDrawable(scene, this, 24f, .38f)
                        }
                    activity
                        .findViewById<FrameLayout>(android.R.id.content)
                        .addView(
                            panel,
                            FrameLayout.LayoutParams(240, 180).apply {
                                leftMargin = 100
                                topMargin = 180
                            },
                        )
                    mapView.getMapAsync {
                        map = it
                        ready.countDown()
                    }
                }
                assertTrue(ready.await(5, TimeUnit.SECONDS))
                for ((name, color, channel) in
                    listOf(Triple("green", "#178f3c", 1), Triple("red", "#9c283c", 0))) {
                    val rendered = CountDownLatch(1)
                    val listener = MapView.OnDidFinishRenderingFrameListener { fully, _, _ ->
                        if (fully) rendered.countDown()
                    }
                    scenario.onActivity {
                        mapView.addOnDidFinishRenderingFrameListener(listener)
                        map.setStyle(
                            Style.Builder()
                                .fromJson(
                                    """{"version":8,"sources":{},"layers":[{"id":"test-background","type":"background","paint":{"background-color":"$color"}}]}"""
                                )
                        )
                    }
                    assertTrue(
                        "The synthetic $name map must render",
                        rendered.await(5, TimeUnit.SECONDS),
                    )
                    scenario.onActivity {
                        mapView.removeOnDidFinishRenderingFrameListener(listener)
                    }
                    val copied = CountDownLatch(1)
                    val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                    var attempts = 0
                    var failure: String? = null
                    scenario.onActivity { activity ->
                        fun sampleNextFrame() {
                            panel.viewTreeObserver.registerFrameCommitCallback {
                                val position = IntArray(2).also(panel::getLocationInWindow)
                                val x = position[0] + panel.width / 2
                                val y = position[1] + panel.height / 2
                                PixelCopy.request(
                                    activity.window,
                                    Rect(x, y, x + 1, y + 1),
                                    bitmap,
                                    { result ->
                                        attempts++
                                        val pixel = bitmap.getPixel(0, 0)
                                        val dominant =
                                            if (channel == 1) Color.green(pixel) - Color.red(pixel)
                                            else Color.red(pixel) - Color.green(pixel)
                                        if (result == PixelCopy.SUCCESS && dominant >= 8)
                                            copied.countDown()
                                        else if (attempts < 4)
                                            panel.postOnAnimation { sampleNextFrame() }
                                        else {
                                            failure =
                                                "$name texture did not reach glass after $attempts committed frames: $pixel / $result"
                                            copied.countDown()
                                        }
                                    },
                                    Handler(Looper.getMainLooper()),
                                )
                            }
                            panel.invalidate()
                        }
                        sampleNextFrame()
                    }
                    assertTrue(copied.await(5, TimeUnit.SECONDS))
                    bitmap.recycle()
                    assertNull(failure)
                    println("GPU $name map reached glass within $attempts committed frame(s)")
                }
                scenario.onActivity {
                    assertNotNull(scene.liveBackdrop)
                    assertNull(scene.bitmap)
                    GlassScene::class
                        .java
                        .getDeclaredField("pending")
                        .apply { isAccessible = true }
                        .setBoolean(scene, false)
                }
            }
        } finally {
            settings.appearance = previous
            Ui.configure(context)
        }
    }
}
