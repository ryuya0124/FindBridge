package dev.findbridge.android

import android.os.SystemClock
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.webkit.WebViewCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic public coordinates. This verifies the adapter, not a real Google account login. */
@RunWith(AndroidJUnit4::class)
class GoogleDataTest {
    @Test
    fun markerSlotIdentitySurvivesNavigationAndNeverTriggersRemoteActions() {
        val ready = CountDownLatch(1)
        val verification = CountDownLatch(1)
        var result: JSONObject? = null
        ActivityScenario.launch(AuthActivity::class.java).use { scenario ->
            lateinit var web: WebView
            scenario.onActivity { activity ->
                web =
                    AuthActivity::class
                        .java
                        .getDeclaredField("webView")
                        .apply { isAccessible = true }
                        .get(activity) as WebView
                web.stopLoading()
                WebViewCompat.addWebMessageListener(
                    web,
                    "FindBridgeGoogleState",
                    GoogleProtocol.dataOrigins,
                ) { _, message, _, _, _ ->
                    if (message.data == "verification") verification.countDown()
                }
                WebViewCompat.addWebMessageListener(
                    web,
                    "FindBridgeGoogleData",
                    GoogleProtocol.dataOrigins,
                ) { _, message, origin, mainFrame, _ ->
                    assertEquals("https://www.google.com", origin.toString().trimEnd('/'))
                    assertTrue(mainFrame)
                    val snapshot = JSONObject(message.data!!)
                    if (snapshot.optBoolean("complete")) {
                        result = snapshot
                        ready.countDown()
                    }
                }
                val script =
                    activity.assets
                        .open("google-devices.js")
                        .bufferedReader()
                        .use { it.readText() }
                        .replace("__FINDBRIDGE_GOOGLE_INITIAL_INDEX__", "-1")
                WebViewCompat.addDocumentStartJavaScript(web, script, GoogleProtocol.dataOrigins)
                web.loadDataWithBaseURL(
                    GoogleProtocol.FIND_URL,
                    """
                    <!doctype html><html><body>
                    <div role="dialog" id="consent"><button id="acknowledge" onclick="window.fixtureAcknowledgements++;document.getElementById('consent').hidden=true">Synthetic confirmation</button></div>
                    <div id="catalog">
                      <div role="button" data-index="0" onclick="selectDevice(0)"><img alt="Device image">Fixture Phone A</div>
                      <div role="button" data-index="1" onclick="selectDevice(1)"><img alt="Device image">Fixture Phone B</div>
                    </div>
                    <div id="details" hidden>
                      <button aria-label="Back" onclick="document.getElementById('catalog').hidden=false;document.getElementById('details').hidden=true">Back</button>
                      <div id="name"></div><div id="status">Last seen: 5 minutes ago</div><div>54%</div>
                    </div>
                    <button onclick="window.remoteActionCount++">Play sound</button>
                    <button onclick="window.remoteActionCount++">Factory reset</button>
                    <div id="markers"></div>
                    <script>
                    window.remoteActionCount=0;
                    window.fixtureAcknowledgements=0;
                    function selectDevice(index) {
                      document.getElementById('name').textContent=index===0?'Fixture Phone A':'Fixture Phone B';
                      document.getElementById('catalog').hidden=true;
                      document.getElementById('details').hidden=false;
                      document.getElementById('markers').innerHTML='<div data-slot-id="'+index+'" data-location-lat="'+(index===0?0:35.68)+'" data-location-lng="'+(index===0?139:139.76)+'"></div>';
                    }
                    </script></body></html>
                    """
                        .trimIndent(),
                    "text/html",
                    "UTF-8",
                    null,
                )
            }
            assertTrue(
                "Official confirmation dialogs must be handed to the owner",
                verification.await(5, TimeUnit.SECONDS),
            )
            SystemClock.sleep(700)
            assertNull("The reader must wait for confirmation", result)
            val confirmation = CountDownLatch(1)
            scenario.onActivity {
                web.evaluateJavascript("window.fixtureAcknowledgements") { value ->
                    assertEquals("0", value)
                    confirmation.countDown()
                }
                web.evaluateJavascript("document.getElementById('acknowledge').click()", null)
            }
            assertTrue(confirmation.await(2, TimeUnit.SECONDS))
            assertTrue(
                "The Google DOM adapter must complete its read-only cycle",
                ready.await(25, TimeUnit.SECONDS),
            )
            val devices = GoogleProtocol.devices(result!!).sortedBy { it.name }
            assertEquals(2, devices.size)
            assertEquals("Fixture Phone A", devices[0].name)
            assertEquals(0.0, devices[0].latitude!!, 0.0)
            assertEquals(139.0, devices[0].longitude!!, 0.0)
            assertEquals(35.68, devices[1].latitude!!, 0.0)
            assertEquals(139.76, devices[1].longitude!!, 0.0)
            assertTrue(
                devices.all { it.battery == 0.54 && it.reportedStatus.startsWith("Last seen") }
            )
            assertTrue(devices.all { it.provider == DeviceProvider.GOOGLE && it.timestamp == 0L })
            val checked = CountDownLatch(1)
            scenario.onActivity {
                web.evaluateJavascript(
                    "[...document.querySelectorAll('button')].find(e=>e.textContent==='Play sound').click()",
                    null,
                )
                web.evaluateJavascript("window.remoteActionCount") { value ->
                    assertEquals("0", value)
                    checked.countDown()
                }
            }
            assertTrue(checked.await(2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun invalidCoordinatesCannotBecomeMapPinsAndSensitivePathsCannotOpen() {
        val snapshot =
            JSONObject(
                """{"devices":[{"name":"Fixture","index":1,"latitude":999,"longitude":139,"battery":101}]}"""
            )
        val device = GoogleProtocol.devices(snapshot).single()
        assertFalse(device.located)
        assertNull(device.latitude)
        assertNull(device.longitude)
        assertNull(device.battery)
        assertEquals(0L, device.timestamp)
        assertTrue(GoogleProtocol.isDataPage(GoogleProtocol.FIND_URL))
        assertFalse(GoogleProtocol.isPage("https://www.google.com/android/find/lock"))
        assertFalse(GoogleProtocol.isPage("https://www.google.com.evil.invalid/android/find/"))
        assertFalse(GoogleProtocol.isPage("http://www.google.com/android/find/"))
        assertFalse(GoogleProtocol.isPage("https://www.google.com:444/android/find/"))
        assertFalse(GoogleProtocol.isPage("https://www.google.com/android/find/erase"))
    }
}
