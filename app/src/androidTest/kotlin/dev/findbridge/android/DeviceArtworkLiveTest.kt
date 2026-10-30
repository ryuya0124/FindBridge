package dev.findbridge.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Only public hardware illustrations are exported; no names, coordinates or account data. */
class DeviceArtworkLiveTest {
    @Test
    fun bothProvidersReturnTheirOriginalArtwork() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("artread") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val apple = AppleRepository.get(context)
        val google = GoogleRepository.get(context)
        val catalog = CountDownLatch(1)
        val listener = AppleRepository.Listener {
            if (
                !apple.loading &&
                    !google.loading &&
                    apple.devices.isNotEmpty() &&
                    google.devices.isNotEmpty()
            )
                catalog.countDown()
        }
        instrumentation.runOnMainSync {
            apple.addListener(listener)
            google.addListener(listener)
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                assertTrue(
                    "Both existing sessions must return their catalog",
                    catalog.await(100, TimeUnit.SECONDS),
                )
                val all = apple.devices + google.devices
                assertTrue(
                    "Every Google device must retain its original illustration URL",
                    google.devices.all { it.imageUrl.isNotEmpty() },
                )
                assertTrue(
                    "Apple must return its hardware artwork metadata",
                    apple.devices.any { it.imageUrl.isNotEmpty() },
                )
                val unique = all.filter { it.imageUrl.isNotEmpty() }.distinctBy { it.imageUrl }
                File(context.cacheDir, "apple-artwork-url-probe.json")
                    .writeText(
                        org.json
                            .JSONArray(
                                unique
                                    .filter { it.provider == DeviceProvider.APPLE }
                                    .map { it.imageUrl }
                            )
                            .toString()
                    )
                val images = CountDownLatch(unique.size)
                val googleLoaded = AtomicInteger()
                val appleLoaded = AtomicInteger()
                val completed = mutableListOf<Pair<DeviceProvider, Bitmap>>()
                instrumentation.runOnMainSync {
                    unique.forEach { device ->
                        DeviceImages.load(device.imageUrl) { bitmap ->
                            if (bitmap != null) {
                                completed += device.provider to bitmap
                                if (device.provider == DeviceProvider.GOOGLE)
                                    googleLoaded.incrementAndGet()
                                else appleLoaded.incrementAndGet()
                            }
                            images.countDown()
                        }
                    }
                }
                assertTrue("Artwork downloads must finish", images.await(60, TimeUnit.SECONDS))
                assertEquals(
                    "Google illustrations must decode",
                    unique.count { it.provider == DeviceProvider.GOOGLE },
                    googleLoaded.get(),
                )
                assertTrue("Original Apple illustrations must decode", appleLoaded.get() > 0)
                File(context.cacheDir, "device-artwork-counts.json")
                    .writeText(
                        org.json
                            .JSONObject()
                            .put("google", googleLoaded.get())
                            .put("apple", appleLoaded.get())
                            .put(
                                "appleMissing",
                                unique.count { it.provider == DeviceProvider.APPLE } -
                                    appleLoaded.get(),
                            )
                            .toString()
                    )
                assertEquals(
                    "All returned Apple artwork must decode",
                    unique.count { it.provider == DeviceProvider.APPLE },
                    appleLoaded.get(),
                )
                scenario.onActivity { activity ->
                    val keys =
                        MainActivity::class
                            .java
                            .getDeclaredField("markerKinds")
                            .apply { isAccessible = true }
                            .get(activity) as Map<*, *>
                    assertTrue(
                        "Visible map markers must finish loading their artwork",
                        keys.values.none { it.toString().contains(":pending") },
                    )
                    fun sources(view: android.view.View): List<String> {
                        if (view is DeviceGlyph)
                            return listOfNotNull(
                                DeviceGlyph::class
                                    .java
                                    .getDeclaredField("artworkUrl")
                                    .apply { isAccessible = true }
                                    .get(view) as? String
                            )
                        return if (view is android.view.ViewGroup)
                            (0 until view.childCount).flatMap { sources(view.getChildAt(it)) }
                        else emptyList()
                    }
                    val rendered = sources(activity.window.decorView)
                    assertTrue(
                        "Google rows must use their original images",
                        google.devices.all { it.imageUrl in rendered },
                    )
                }
                instrumentation.runOnMainSync {
                    val proof =
                        completed.filter { it.first == DeviceProvider.GOOGLE } +
                            completed.filter { it.first == DeviceProvider.APPLE }.take(12)
                    val sheet =
                        Bitmap.createBitmap(
                            600,
                            ((proof.size + 5) / 6) * 116,
                            Bitmap.Config.ARGB_8888,
                        )
                    val canvas = Canvas(sheet)
                    canvas.drawColor(Color.WHITE)
                    val paint =
                        Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            isFilterBitmap = true
                            textSize = 11f
                            color = Color.DKGRAY
                        }
                    proof.forEachIndexed { index, pair ->
                        val x = (index % 6) * 100f
                        val y = (index / 6) * 116f
                        val scale = minOf(88f / pair.second.width, 88f / pair.second.height)
                        val w = pair.second.width * scale
                        val h = pair.second.height * scale
                        canvas.drawBitmap(
                            pair.second,
                            null,
                            RectF(
                                x + (100 - w) / 2,
                                y + (96 - h) / 2,
                                x + (100 + w) / 2,
                                y + (96 + h) / 2,
                            ),
                            paint,
                        )
                        canvas.drawText(
                            if (pair.first == DeviceProvider.GOOGLE) "Google" else "Apple",
                            x + 30,
                            y + 108,
                            paint,
                        )
                    }
                    File(context.cacheDir, "device-artwork-proof.png").outputStream().use { output
                        ->
                        sheet.compress(Bitmap.CompressFormat.PNG, 100, output)
                    }
                    sheet.recycle()
                }
                println(
                    "Original hardware artwork: google=${googleLoaded.get()} apple=${appleLoaded.get()} appleMissing=${unique.count { it.provider == DeviceProvider.APPLE } - appleLoaded.get()}"
                )
            }
        } finally {
            instrumentation.runOnMainSync {
                apple.removeListener(listener)
                google.removeListener(listener)
            }
        }
    }
}
