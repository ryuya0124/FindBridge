package dev.findbridge.android

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in, read-only test using an already-authorized local session. Never logs device data. */
@RunWith(AndroidJUnit4::class)
class NativeSessionLiveTest {
    @Test
    fun persistedSessionFetchesDevicesWithoutAWebViewAndSoundStaysDisabled() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var repository: AppleRepository
        val finished = CountDownLatch(1)
        val listener = AppleRepository.Listener {
            if (!repository.loading) finished.countDown()
        }
        instrumentation.runOnMainSync {
            repository = AppleRepository.get(instrumentation.targetContext)
            assertTrue("An authorized encrypted session must already exist", repository.hasSession)
            repository.addListener(listener)
            repository.refresh()
        }
        assertTrue("Native refresh timed out", finished.await(40, TimeUnit.SECONDS))
        instrumentation.runOnMainSync { repository.removeListener(listener) }
        assertTrue("Direct native HTTP transport must succeed", repository.nativeTransportVerified)
        assertTrue("The account must return at least one device", repository.devices.isNotEmpty())
        assertTrue("Expected at least one actual location", repository.devices.any { it.located })

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val cameraReady = CountDownLatch(1)
            var cameraContainsDevices = false
            scenario.onActivity { activity ->
                val mapView =
                    activity.findViewById<org.maplibre.android.maps.MapView>(MainActivity.MAP_ID)
                mapView.getMapAsync { map ->
                    activity.window.decorView.postDelayed(
                        {
                            val sheet = activity.findViewById<View>(MainActivity.SHEET_ID)
                            cameraContainsDevices =
                                repository.devices
                                    .filter { it.located && !it.family }
                                    .all {
                                        val screen =
                                            map.projection.toScreenLocation(
                                                org.maplibre.android.geometry.LatLng(
                                                    it.latitude!!,
                                                    it.longitude!!,
                                                )
                                            )
                                        screen.x in 0f..mapView.width.toFloat() &&
                                            screen.y in 0f..mapView.height.toFloat() &&
                                            !(screen.x >= sheet.left &&
                                                screen.x <= sheet.right &&
                                                screen.y >= sheet.top &&
                                                screen.y <= sheet.bottom)
                                    }
                            cameraReady.countDown()
                        },
                        2000,
                    )
                }
            }
            assertTrue("Native camera did not settle", cameraReady.await(15, TimeUnit.SECONDS))
            assertTrue(
                "Device markers must fit above the native bottom sheet",
                cameraContainsDevices,
            )
            scenario.onActivity { activity ->
                val device = repository.devices.first { it.located }
                fun findLabel(view: View): TextView? {
                    if (view is TextView && view.text.toString() == device.name) return view
                    if (view is ViewGroup) {
                        for (index in 0 until view.childCount) findLabel(view.getChildAt(index))
                            ?.let {
                                return it
                            }
                    }
                    return null
                }
                val label = findLabel(activity.window.decorView)
                assertNotNull("Device must be rendered as a native label", label)
                ((label!!.parent as View).parent as View).performClick()
                val sound = activity.findViewById<View>(MainActivity.SOUND_ID)
                assertNotNull(sound)
                assertFalse("No sound must be possible during debugging", sound.isEnabled)
            }
        }
    }
}
