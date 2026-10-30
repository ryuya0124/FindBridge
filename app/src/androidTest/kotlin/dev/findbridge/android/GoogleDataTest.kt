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
                      <section data-index="0"><div role="button" onclick="selectDevice(0)"><img alt="Device image">Fixture Phone A</div></section>
                      <section data-index="1"><div role="button" onclick="selectDevice(1)"><img alt="Device image">Fixture Phone B</div></section>
                    </div>
                    <div id="details" hidden>
                      <button aria-label="戻る" onclick="document.getElementById('catalog').hidden=false;document.getElementById('details').hidden=true">Back</button>
                      <div id="name"></div><div id="location-status">Last seen: 5 minutes ago</div><div>54%</div>
                      <div data-battery="50-charging" style="width:10px;height:10px"></div>
                      <div><span data-wifi="4" style="display:inline-block;width:10px;height:10px"></span><span>Fixture Wi-Fi</span></div>
                      <button onclick="document.getElementById('settings').hidden=false">settings</button>
                    </div>
                    <div id="settings" role="dialog" hidden>
                      <div class="Z3br3c">
                        <div class="KJqPCc" id="info-name"></div>
                        <div class="e84yjf">Google Pixel 9</div>
                        <div class="e84yjf" id="fixture-imei"></div>
                        <div class="e84yjf">MEID: 00000000000001</div>
                        <div class="e84yjf">First registered: Oct 1, 2026</div>
                        <div class="e84yjf">Manufacturer: Fixture Manufacturer</div>
                      </div>
                      <button aria-label="Close" onclick="document.getElementById('settings').hidden=true">close</button>
                    </div>
                    <button onclick="window.remoteActionCount++">Play sound</button>
                    <button onclick="window.remoteActionCount++">Factory reset</button>
                    <div id="markers"></div>
                    <script>
                    window.remoteActionCount=0;
                    window.fixtureAcknowledgements=0;
                    function selectDevice(index) {
                      document.getElementById('name').textContent=index===0?'Fixture Phone A':'Fixture Phone B';
                      document.getElementById('info-name').textContent=document.getElementById('name').textContent;
                      document.getElementById('fixture-imei').textContent='IMEI: 00000000000000'+(index+1);
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
            assertEquals("000000000000001", devices[0].metadata.imei)
            assertEquals("000000000000002", devices[1].metadata.imei)
            assertTrue(devices.all { it.model == "Google Pixel 9" && it.charging })
            assertTrue(devices.all { it.metadata.meid == "00000000000001" })
            assertTrue(devices.all { it.metadata.firstRegistered == "Oct 1, 2026" })
            assertTrue(devices.all { it.metadata.manufacturer == "Fixture Manufacturer" })
            assertTrue(devices.all { it.metadata.network == "Fixture Wi-Fi" })
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
    fun detachedRefreshDoesNotWaitForAnimationFrames() {
        val completed = CountDownLatch(1)
        var result: JSONObject? = null
        ActivityScenario.launch(AuthActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val web =
                    AuthActivity::class
                        .java
                        .getDeclaredField("webView")
                        .apply {
                            isAccessible = true
                        }
                        .get(activity) as WebView
                web.stopLoading()
                (web.parent as android.view.ViewGroup).removeView(web)
                val exact = android.view.View.MeasureSpec.EXACTLY
                web.measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(1080, exact),
                    android.view.View.MeasureSpec.makeMeasureSpec(2400, exact),
                )
                web.layout(0, 0, 1080, 2400)
                web.onResume()
                WebViewCompat.addWebMessageListener(
                    web,
                    "FindBridgeGoogleData",
                    GoogleProtocol.dataOrigins,
                ) { _, message, _, _, _ ->
                    val snapshot = JSONObject(message.data!!)
                    if (snapshot.optBoolean("complete")) {
                        result = snapshot
                        completed.countDown()
                    }
                }
                WebViewCompat.addDocumentStartJavaScript(
                    web,
                    "window.requestAnimationFrame=()=>0;",
                    GoogleProtocol.dataOrigins,
                )
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
                    <!doctype html><body>
                    <div role="button" data-index="0" onclick="this.hidden=true;document.body.insertAdjacentHTML('beforeend','<h1>Fixture Tablet</h1><div data-slot-id=0 data-location-lat=35.68 data-location-lng=139.76></div>')"><img alt="Fixture">Fixture Tablet</div>
                    </body>
                    """
                        .trimIndent(),
                    "text/html",
                    "UTF-8",
                    null,
                )
            }
            assertTrue(
                "A detached refresh must finish even when rAF never fires",
                completed.await(5, TimeUnit.SECONDS),
            )
            val device = GoogleProtocol.devices(result!!).single()
            assertEquals("Fixture Tablet", device.name)
            assertEquals(35.68, device.latitude!!, 0.0)
        }
    }

    @Test
    fun invalidCoordinatesCannotBecomeMapPinsAndSensitivePathsCannotOpen() {
        val snapshot =
            JSONObject(
                """{"devices":[{"name":"Fixture","index":1,"latitude":999,"longitude":139,"battery":101,"imageUrl":"https://lh3.googleusercontent.com/fixture-art"}]}"""
            )
        val device = GoogleProtocol.devices(snapshot).single()
        assertFalse(device.located)
        assertNull(device.latitude)
        assertNull(device.longitude)
        assertNull(device.battery)
        assertEquals("https://lh3.googleusercontent.com/fixture-art", device.imageUrl)
        snapshot
            .getJSONArray("devices")
            .getJSONObject(0)
            .put("imageUrl", "file:///data/local/private.png")
        assertEquals("", GoogleProtocol.devices(snapshot).single().imageUrl)
        assertEquals(0L, device.timestamp)
        assertTrue(GoogleProtocol.isDataPage(GoogleProtocol.FIND_URL))
        assertTrue(GoogleProtocol.isDataPage("https://www.google.com/android/find/u/1/"))
        assertFalse(GoogleProtocol.isPage("https://www.google.com/android/find/u/1/erase"))
        assertFalse(GoogleProtocol.isPage("https://owner@www.google.com/android/find/"))
        assertTrue(GoogleProtocol.isPage("https://accounts.youtube.com/accounts/SetSID?fixture=1"))
        assertTrue(GoogleProtocol.isPage("https://www.google.com/accounts/CheckCookie?fixture=1"))
        assertFalse(GoogleProtocol.isPage("https://accounts.youtube.com/watch"))
        assertFalse(
            GoogleProtocol.isPage("https://accounts.youtube.com.evil.invalid/accounts/SetSID")
        )
        assertFalse(GoogleProtocol.isPage("https://www.google.com/android/find/lock"))
        assertFalse(GoogleProtocol.isPage("https://www.google.com.evil.invalid/android/find/"))
        assertFalse(GoogleProtocol.isPage("http://www.google.com/android/find/"))
        assertFalse(GoogleProtocol.isPage("https://www.google.com:444/android/find/"))
        assertFalse(GoogleProtocol.isPage("https://www.google.com/android/find/erase"))
    }

    @Test
    fun delayedAccountMetadataCompletesTheCatalogOnMultiAccountPaths() {
        val ready = CountDownLatch(1)
        ActivityScenario.launch(AuthActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val web =
                    AuthActivity::class
                        .java
                        .getDeclaredField("webView")
                        .apply {
                            isAccessible = true
                        }
                        .get(activity) as WebView
                web.stopLoading()
                WebViewCompat.addWebMessageListener(
                    web,
                    "FindBridgeGoogleData",
                    GoogleProtocol.dataOrigins,
                ) { _, message, _, _, _ ->
                    val snapshot = JSONObject(message.data!!)
                    if (
                        snapshot.optString("account") == "owner@example.com" &&
                            snapshot.optBoolean("complete")
                    ) {
                        assertEquals(1, GoogleProtocol.devices(snapshot).size)
                        ready.countDown()
                    } else if (snapshot.optBoolean("complete")) {
                        web.evaluateJavascript(
                            "document.body.insertAdjacentHTML('beforeend','<a aria-label=\"Google Account: owner@example.com\"></a>')",
                            null,
                        )
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
                    "https://www.google.com/android/find/u/1/",
                    """
                    <!doctype html><body>
                    <div role="button" data-index="0" onclick="this.hidden=true;document.body.insertAdjacentHTML('beforeend','<h1>Fixture Tablet</h1><div data-slot-id=0 data-location-lat=35.68 data-location-lng=139.76></div>')"><img alt="Fixture">Fixture Tablet</div>
                    </body>
                    """
                        .trimIndent(),
                    "text/html",
                    "UTF-8",
                    null,
                )
            }
            assertTrue(
                "Late account metadata must produce a new completed snapshot",
                ready.await(5, TimeUnit.SECONDS),
            )
        }
    }
}
