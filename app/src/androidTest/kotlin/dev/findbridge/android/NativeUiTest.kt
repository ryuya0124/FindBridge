package dev.findbridge.android

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.maps.MapView

@RunWith(AndroidJUnit4::class)
class NativeUiTest {
    @Test
    fun primaryScreenUsesNativeViewsAndNativeMap() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<View>(MainActivity.MAP_ID) is MapView)
                assertNotNull(activity.findViewById<View>(MainActivity.SHEET_ID))
                fun containsWebView(view: View): Boolean =
                    view is WebView ||
                        view is ViewGroup &&
                            (0 until view.childCount).any { containsWebView(view.getChildAt(it)) }
                assertFalse(
                    "Device UI must not embed a browser",
                    containsWebView(activity.window.decorView),
                )
            }
        }
    }

    @Test
    fun jsonWithoutLocationRemainsUnlocated() {
        val json =
            org.json.JSONObject(
                """{"id":"fixture","name":"Offline iPad","deviceStatus":"203","location":null}"""
            )
        val device = AppleDevice.fromJson(json)
        assertFalse(device.located)
        assertTrue(device.latitude == null && device.longitude == null)
    }
}
