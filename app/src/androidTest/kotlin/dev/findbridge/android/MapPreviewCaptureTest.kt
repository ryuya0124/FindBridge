package dev.findbridge.android

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshotter

/** Explicit asset-generation task; fetches public map tiles, never account data. */
class MapPreviewCaptureTest {
    @Test
    fun captureRealMapStyleSamples() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("capturePreviews") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        for ((name, mode, dark) in
            listOf(
                Triple("map_preview_standard", MapMode.STANDARD, false),
                Triple("map_preview_standard_dark", MapMode.STANDARD, true),
                Triple("map_preview_aerial", MapMode.AERIAL, false),
                Triple("map_preview_hybrid", MapMode.HYBRID, false),
            )) {
            val completed = CountDownLatch(1)
            var error: String? = null
            lateinit var snapshotter: MapSnapshotter
            instrumentation.runOnMainSync {
                MapLibre.getInstance(context)
                snapshotter =
                    MapSnapshotter(
                        context,
                        MapSnapshotter.Options(320, 192)
                            .withPixelRatio(1f)
                            .withStyleBuilder(
                                Style.Builder().fromJson(MapStyles.create(context, mode, dark))
                            )
                            .withCameraPosition(
                                CameraPosition.Builder()
                                    .target(LatLng(35.665, 139.77))
                                    .zoom(14.4)
                                    .build()
                            )
                            .withLogo(false)
                            .withAttribution(false),
                    )
                snapshotter.start(
                    { snapshot ->
                        File(context.cacheDir, "$name.webp").outputStream().use {
                            snapshot.bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 82, it)
                        }
                        completed.countDown()
                    },
                    { failure ->
                        error = failure
                        completed.countDown()
                    },
                )
            }
            try {
                assertTrue(
                    "Public tiles for $name must render",
                    completed.await(45, TimeUnit.SECONDS),
                )
                assertNull(error)
            } finally {
                instrumentation.runOnMainSync { snapshotter.cancel() }
            }
        }
    }
}
