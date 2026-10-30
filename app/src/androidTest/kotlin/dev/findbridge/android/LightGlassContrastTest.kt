package dev.findbridge.android

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Samples the real hardware shader over dark aerial/water pixels, not a CPU formula copy. */
class LightGlassContrastTest {
    @Test
    fun smallSecondaryTextRemainsReadableAtMaximumTransparency() {
        verify(Appearance.LIGHT, Color.rgb(2, 10, 20))
    }

    @Test
    fun darkGlassKeepsSecondaryTextReadableOverBrightImagery() {
        verify(Appearance.DARK, Color.rgb(248, 250, 252))
    }

    private fun verify(mode: Appearance, sceneColor: Int) {
        assumeTrue(android.os.Build.VERSION.SDK_INT >= 33)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = AppearanceSettings(context)
        val previous = settings.appearance
        settings.appearance = mode
        val copied = CountDownLatch(3)
        val samples = mutableListOf<Bitmap>()
        val results = mutableListOf<Int>()
        lateinit var scene: GlassScene
        var requirements = emptyList<Pair<Int, Double>>()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    assumeTrue(activity.window.decorView.isHardwareAccelerated)
                    requirements =
                        listOf(
                            Ui.muted to 4.5,
                            Ui.blue to if (mode == Appearance.DARK) 3.0 else 4.5,
                        )
                    val root = activity.findViewById<FrameLayout>(android.R.id.content)
                    val stack =
                        LinearLayout(activity).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(20, 160, 20, 0)
                        }
                    root.addView(stack, FrameLayout.LayoutParams(-1, -1))
                    scene = GlassScene(stack).apply { intensity = 1f }
                    val backdrop =
                        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply {
                            eraseColor(sceneColor)
                        }
                    GlassScene::class
                        .java
                        .getDeclaredField("bitmap")
                        .apply { isAccessible = true }
                        .set(scene, backdrop)
                    val panels =
                        listOf(.83f, .72f, .18f).map { material ->
                            View(activity).apply {
                                background = GlassDrawable(scene, this, 24f, material)
                                stack.addView(
                                    this,
                                    LinearLayout.LayoutParams(-1, 180).apply { bottomMargin = 30 },
                                )
                            }
                        }
                    stack.viewTreeObserver.registerFrameCommitCallback {
                        panels.forEach { panel ->
                            val location = IntArray(2)
                            panel.getLocationInWindow(location)
                            val x = location[0] + panel.width / 2
                            val y = location[1] + panel.height / 2
                            val sample = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                            samples += sample
                            PixelCopy.request(
                                activity.window,
                                Rect(x, y, x + 1, y + 1),
                                sample,
                                { result ->
                                    results += result
                                    copied.countDown()
                                },
                                Handler(Looper.getMainLooper()),
                            )
                        }
                    }
                    stack.invalidate()
                }
                assertTrue(
                    "Three hardware-rendered materials must be copied",
                    copied.await(10, TimeUnit.SECONDS),
                )
                assertEquals(
                    listOf(PixelCopy.SUCCESS, PixelCopy.SUCCESS, PixelCopy.SUCCESS),
                    results,
                )
                for (sample in samples) {
                    for ((color, minimum) in requirements) {
                        val ratio = contrast(color, sample.getPixel(0, 0))
                        assertTrue(
                            "Glass contrast must be >= $minimum; was $ratio",
                            ratio >= minimum,
                        )
                    }
                }
                scenario.onActivity {
                    (scene.javaClass
                            .getDeclaredField("mapView")
                            .apply { isAccessible = true }
                            .get(scene) as View)
                        .let { (it.parent as FrameLayout).removeView(it) }
                    scene.dispose()
                }
            }
        } finally {
            settings.appearance = previous
            Ui.configure(context)
            samples.forEach { it.recycle() }
        }
    }

    private fun contrast(a: Int, b: Int): Double {
        fun luminance(color: Int): Double {
            fun channel(value: Int): Double {
                val x = value / 255.0
                return if (x <= .04045) x / 12.92 else Math.pow((x + .055) / 1.055, 2.4)
            }
            return channel(Color.red(color)) * .2126 +
                channel(Color.green(color)) * .7152 +
                channel(Color.blue(color)) * .0722
        }
        val x = luminance(a)
        val y = luminance(b)
        return (maxOf(x, y) + .05) / (minOf(x, y) + .05)
    }
}
