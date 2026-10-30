package dev.findbridge.android

import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView

class MapCameraTest {
    @Test
    fun nearbyDevicesDoNotOverZoomAndStyleChangesPreserveTheCamera() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val sync = LocationSyncRepository.get(context)
        assumeFalse("Preserve an existing owner source", sync.configured)
        val settings = AppearanceSettings(context)
        val oldMode = settings.mapMode
        val oldMotion = settings.reduceMotion
        val oldZoom = settings.overviewZoom
        val file = File(context.cacheDir, "camera-fixture.json")
        file.writeText(
            """{"source":"TEST FIXTURE","people":[{"id":"camera-a","name":"Fixture A","location":{"latitude":35.68,"longitude":139.76}},{"id":"camera-b","name":"Fixture B","location":{"latitude":35.68001,"longitude":139.76001}}]}"""
        )
        val loaded = CountDownLatch(1)
        val listener = AppleRepository.Listener { if (!sync.loading) loaded.countDown() }
        fun find(view: View, text: String): View? {
            if (view is TextView && view.text.toString() == text) return view
            if (view is ViewGroup)
                for (index in 0 until view.childCount) find(view.getChildAt(index), text)?.let {
                    return it
                }
            return null
        }
        try {
            settings.reduceMotion = true
            settings.overviewZoom = 13.25
            instrumentation.runOnMainSync {
                sync.addListener(listener)
                sync.importFile(Uri.fromFile(file))
            }
            assertTrue(loaded.await(10, TimeUnit.SECONDS))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var map: MapLibreMap
                lateinit var mapView: MapView
                val ready = CountDownLatch(1)
                scenario.onActivity { activity ->
                    mapView = activity.findViewById(MainActivity.MAP_ID)
                    mapView.getMapAsync { current ->
                        map = current
                        fun selectFixtures() {
                            MainActivity::class
                                .java
                                .getDeclaredMethod("switchTab", Int::class.javaPrimitiveType)
                                .apply { isAccessible = true }
                                .invoke(activity, 0)
                            ready.countDown()
                        }
                        if (current.style?.isFullyLoaded == true) selectFixtures()
                        else
                            mapView.addOnDidFinishLoadingStyleListener {
                                activity.window.decorView.post { selectFixtures() }
                            }
                    }
                }
                assertTrue("Map style must load", ready.await(30, TimeUnit.SECONDS))
                scenario.onActivity {
                    assertEquals(
                        "Automatic fitting respects the saved overview scale",
                        13.25,
                        map.cameraPosition.zoom,
                        .001,
                    )
                    map.moveCamera(
                        CameraUpdateFactory.newCameraPosition(
                            CameraPosition.Builder()
                                .target(LatLng(35.68, 139.76))
                                .zoom(12.625)
                                .bearing(31.0)
                                .tilt(40.0)
                                .build()
                        )
                    )
                }
                for (mode in listOf(MapMode.AERIAL, MapMode.STANDARD, MapMode.HYBRID)) {
                    val styled = CountDownLatch(1)
                    val finished = MapView.OnDidFinishLoadingStyleListener { styled.countDown() }
                    scenario.onActivity { activity ->
                        mapView.addOnDidFinishLoadingStyleListener(finished)
                        activity.findViewById<View>(MainActivity.MAP_OPTIONS_ID).performClick()
                        find(
                                activity.findViewById(MainActivity.PANEL_ID),
                                activity.getString(mode.titleRes),
                            )!!
                            .performClick()
                    }
                    assertTrue("Map style must finish", styled.await(30, TimeUnit.SECONDS))
                    scenario.onActivity { activity ->
                        mapView.removeOnDidFinishLoadingStyleListener(finished)
                        val camera = map.cameraPosition
                        assertEquals(12.625, camera.zoom, .001)
                        assertEquals(31.0, camera.bearing, .001)
                        assertEquals(40.0, camera.tilt, .001)
                        assertEquals(35.68, camera.target!!.latitude, .00001)
                        assertEquals(139.76, camera.target!!.longitude, .00001)
                        find(
                                activity.findViewById(MainActivity.PANEL_ID),
                                activity.getString(R.string.done),
                            )!!
                            .performClick()
                    }
                }
            }
        } finally {
            settings.mapMode = oldMode
            settings.reduceMotion = oldMotion
            settings.overviewZoom = oldZoom
            instrumentation.runOnMainSync {
                sync.removeListener(listener)
                sync.disconnect()
            }
            file.delete()
        }
    }
}
