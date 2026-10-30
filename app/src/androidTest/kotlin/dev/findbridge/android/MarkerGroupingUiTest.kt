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
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView

class MarkerGroupingUiTest {
    @Test
    fun identicalDevicePositionsOpenASelectableNativeList() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val sync = LocationSyncRepository.get(context)
        assumeFalse(sync.configured)
        val settings = AppearanceSettings(context)
        val reduced = settings.reduceMotion
        settings.reduceMotion = true
        val file = File(context.cacheDir, "marker-group-fixture.json")
        file.writeText(
            """{"source":"TEST FIXTURE","items":[{"id":"group-a","name":"Fixture Pencil A","kind":"Pencil","location":{"latitude":35,"longitude":139}},{"id":"group-b","name":"Fixture Pencil B","kind":"Pencil","location":{"latitude":35,"longitude":139}}]}"""
        )
        val imported = CountDownLatch(1)
        val listener = AppleRepository.Listener { if (!sync.loading) imported.countDown() }
        instrumentation.runOnMainSync {
            sync.addListener(listener)
            sync.importFile(Uri.fromFile(file))
        }
        fun find(view: View, text: String): View? {
            if (view is TextView && view.text.toString() == text) return view
            if (view is ViewGroup)
                for (index in 0 until view.childCount) find(view.getChildAt(index), text)?.let {
                    return it
                }
            return null
        }
        try {
            assertTrue(imported.await(10, TimeUnit.SECONDS))
            val ids = sync.pencils.map { it.asDevice().id }
            val ready = CountDownLatch(1)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val view = activity.findViewById<MapView>(MainActivity.MAP_ID)
                    view.getMapAsync { map ->
                        fun prepare() {
                            map.moveCamera(
                                CameraUpdateFactory.newLatLngZoom(LatLng(35.0, 139.0), 14.0)
                            )
                            MainActivity::class
                                .java
                                .getDeclaredMethod("renderMarkers")
                                .apply { isAccessible = true }
                                .invoke(activity)
                            ready.countDown()
                        }
                        if (map.style?.isFullyLoaded == true) prepare()
                        else
                            view.addOnDidFinishLoadingStyleListener {
                                activity.window.decorView.post { prepare() }
                            }
                    }
                }
                assertTrue(ready.await(30, TimeUnit.SECONDS))
                scenario.onActivity { activity ->
                    val groups =
                        MainActivity::class
                            .java
                            .getDeclaredField("markerGroups")
                            .apply { isAccessible = true }
                            .get(activity) as Map<*, *>
                    val group =
                        groups.values.map { it as List<*> }.firstOrNull { ids.all(it::contains) }
                    assertNotNull("Identical locations must have a single counted marker", group)
                    MainActivity::class
                        .java
                        .getDeclaredMethod("showMarkerGroup", List::class.java)
                        .apply { isAccessible = true }
                        .invoke(activity, group)
                    val panel = activity.findViewById<View>(MainActivity.PANEL_ID)
                    assertNotNull(find(panel, "Fixture Pencil A"))
                    assertNotNull(find(panel, "Fixture Pencil B"))
                    (find(panel, "Fixture Pencil B")!!.parent.parent as View).performClick()
                    assertNull(activity.findViewById<View>(MainActivity.PANEL_ID))
                    val selected =
                        MainActivity::class
                            .java
                            .getDeclaredField("selectedId")
                            .apply { isAccessible = true }
                            .get(activity)
                    assertEquals(
                        sync.pencils.first { it.name == "Fixture Pencil B" }.asDevice().id,
                        selected,
                    )
                    assertFalse(
                        "Grouping must preserve actual location data",
                        sync.pencils.any { it.latitude != 35.0 || it.longitude != 139.0 },
                    )
                }
            }
        } finally {
            settings.reduceMotion = reduced
            instrumentation.runOnMainSync {
                sync.removeListener(listener)
                sync.disconnect()
            }
            file.delete()
        }
    }
}
