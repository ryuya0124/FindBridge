package dev.findbridge.android

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ActivityScenario
import androidx.webkit.WebViewCompat
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Every operation uses an isolated HTML fixture. All network requests are blocked. */
class GoogleCommandTest {
    private val account = "fixture@example.invalid"

    private fun target(playing: Boolean = false) =
        GoogleProtocol.devices(
                JSONObject()
                    .put("account", account)
                    .put(
                        "devices",
                        JSONArray()
                            .put(
                                JSONObject()
                                    .put("index", 0)
                                    .put("name", "Fixture Phone")
                                    .put("soundSupported", true)
                                    .put("soundPlaying", playing)
                                    .put("lostModeSupported", true)
                            ),
                    )
            )
            .single()

    @Test
    fun ticketsAreSingleUseAndRevokedOnAccountGenerationOrNewCommand() {
        try {
            val a = GoogleCommandStore.put(account, 7, target(), GoogleCommandType.RING)
            assertNull(GoogleCommandStore.take(a, "other@example.invalid", 7))
            assertNotNull(GoogleCommandStore.take(a, account, 7))
            assertNull(GoogleCommandStore.take(a, account, 7))
            assertTrue(GoogleCommandStore.authorized(a, account, 7))
            assertFalse(GoogleCommandStore.authorized(a, account, 8))
            val b =
                GoogleCommandStore.put(
                    account,
                    7,
                    target(),
                    GoogleCommandType.MARK_LOST,
                    LostModeDraft("", "Fixture"),
                )
            assertFalse(GoogleCommandStore.authorized(a, account, 7))
            GoogleCommandStore.revoke(b)
            assertFalse(GoogleCommandStore.authorized(b, account, 7))
            assertThrows(IllegalArgumentException::class.java) {
                GoogleCommandStore.put(
                    account,
                    7,
                    target(),
                    GoogleCommandType.MARK_LOST,
                    LostModeDraft("", "x".repeat(81)),
                )
            }
        } finally {
            GoogleCommandStore.clear()
        }
    }

    @Test fun ringWaitsForNativeGrantAndRepeatedGrantCannotRingTwice() = run(GoogleCommandType.RING)

    @Test fun stopSoundUsesOnlyTheStopControl() = run(GoogleCommandType.STOP_SOUND)

    @Test fun markLostFillsOnlyContactFieldsAndSubmitsOnce() = run(GoogleCommandType.MARK_LOST)

    @Test
    fun differentAccountNeverPreparesOrSendsAnOperation() =
        run(GoogleCommandType.RING, wrongAccount = true)

    @Test
    fun changedTargetNeverPreparesOrSendsAnOperation() =
        run(GoogleCommandType.RING, wrongTarget = true)

    @Test
    fun cancelBeforeNativeGrantDoesNotSendAnything() = run(GoogleCommandType.RING, cancel = true)

    @Test fun pinChallengeIsNotFilledByTheAdapter() = run(GoogleCommandType.RING, pin = true)

    private fun run(
        type: GoogleCommandType,
        wrongAccount: Boolean = false,
        wrongTarget: Boolean = false,
        cancel: Boolean = false,
        pin: Boolean = false,
    ) {
        val ready = CountDownLatch(1)
        val commitReady = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val verification = CountDownLatch(1)
        var status = ""
        val device = target(type == GoogleCommandType.STOP_SOUND)
        val ticket =
            GoogleCommandStore.put(
                account,
                1,
                device,
                type,
                if (type == GoogleCommandType.MARK_LOST)
                    LostModeDraft("+1 202 555 0100", "Fixture message")
                else null,
            )
        val command = requireNotNull(GoogleCommandStore.take(ticket, account, 1))
        if (wrongTarget) command.put("deviceId", "google:0:000000000000000000000000")
        try {
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
                    web.webViewClient =
                        object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView,
                                request: WebResourceRequest,
                            ) =
                                WebResourceResponse(
                                    "text/plain",
                                    "UTF-8",
                                    ByteArrayInputStream(byteArrayOf()),
                                )

                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest,
                            ) = true
                        }
                    WebViewCompat.addWebMessageListener(
                        web,
                        "FindBridgeGoogleActionState",
                        GoogleProtocol.dataOrigins,
                    ) { _, message, _, _, _ ->
                        val result = JSONObject(message.data!!)
                        assertEquals(ticket, result.getString("ticket"))
                        when (val value = result.getString("status")) {
                            "ready" -> ready.countDown()
                            "commitReady" -> commitReady.countDown()
                            "submitted" -> Unit
                            else -> {
                                status = value
                                finished.countDown()
                            }
                        }
                    }
                    WebViewCompat.addWebMessageListener(
                        web,
                        "FindBridgeGoogleState",
                        GoogleProtocol.dataOrigins,
                    ) { _, message, _, _, _ ->
                        if (message.data == "verification") verification.countDown()
                    }
                    val script =
                        activity.assets
                            .open("google-device-action.js")
                            .bufferedReader()
                            .use { it.readText() }
                            .replace("__FINDBRIDGE_GOOGLE_COMMAND__", command.toString())
                    WebViewCompat.addDocumentStartJavaScript(
                        web,
                        script,
                        GoogleProtocol.dataOrigins,
                    )
                    val email = if (wrongAccount) "another@example.invalid" else account
                    val startingIcon =
                        if (type == GoogleCommandType.STOP_SOUND) "stop_circle" else "sound_sensing"
                    web.loadDataWithBaseURL(
                        GoogleProtocol.FIND_URL,
                        """
                        <!doctype html><body>
                        <a aria-label="Google Account: $email"></a>
                        <section data-index="0" id="catalog"><button onclick="selectFixture()"><img alt="Device">Fixture Phone</button></section>
                        <main id="detail" hidden><h1>Fixture Phone</h1>
                        <button jsname="gnWSqc" id="ring" onclick="ringFixture()">$startingIcon</button>
                        <button jsname="LVPyJb" onclick="lostIntro()">lock</button></main>
                        <button onclick="window.eraseCount++">Factory reset</button>
                        <div id="dialogs"></div>
                        <script>
                        window.remoteCount=0;window.eraseCount=0;window.capturedContact=null;
                        function selectFixture(){document.getElementById('catalog').hidden=true;document.getElementById('detail').hidden=false;
                          if($pin)document.getElementById('dialogs').innerHTML='<div role="dialog"><input id="pin" type="password" aria-label="PIN"><button id="owner-confirm" onclick="this.parentElement.remove()">Verify</button></div>';
                        }
                        function ringFixture(){window.remoteCount++;document.getElementById('ring').textContent='$startingIcon'==='stop_circle'?'sound_sensing':'stop_circle';}
                        function lostIntro(){document.getElementById('dialogs').innerHTML='<div role="dialog"><span>wallet</span><button onclick="lostContact()">Next</button></div>';}
                        function lostContact(){document.getElementById('dialogs').innerHTML='<div role="dialog"><input jsname="HJT2Ge" id="message"><input jsname="vcCt7" id="phone"><button onclick="lostSubmit()">Mark as lost</button></div>';}
                        function lostSubmit(){window.remoteCount++;window.capturedContact=[document.getElementById('phone').value,document.getElementById('message').value];document.getElementById('dialogs').innerHTML='';document.getElementById('detail').insertAdjacentHTML('beforeend','<div jsname="SauT1d">Marked as lost</div>');}
                        </script></body>
                    """
                            .trimIndent(),
                        "text/html",
                        "UTF-8",
                        null,
                    )
                }
                if (pin) {
                    assertTrue(
                        "The official PIN challenge must be shown",
                        verification.await(5, TimeUnit.SECONDS),
                    )
                    val checked = CountDownLatch(1)
                    scenario.onActivity {
                        web.evaluateJavascript("document.getElementById('pin').value") {
                            assertEquals("\"\"", it)
                            checked.countDown()
                        }
                        // This represents the owner completing a synthetic challenge.
                        web.evaluateJavascript(
                            "document.getElementById('owner-confirm').click()",
                            null,
                        )
                    }
                    assertTrue(checked.await(2, TimeUnit.SECONDS))
                }
                if (wrongAccount || wrongTarget) {
                    assertTrue(finished.await(5, TimeUnit.SECONDS))
                    assertEquals(if (wrongAccount) "ACCOUNT_MISMATCH" else "TARGET_CHANGED", status)
                } else {
                    assertTrue(
                        "A validated device must become ready",
                        ready.await(5, TimeUnit.SECONDS),
                    )
                    val checked = CountDownLatch(1)
                    scenario.onActivity {
                        web.evaluateJavascript("window.remoteCount") {
                            assertEquals("0", it)
                            checked.countDown()
                        }
                        if (cancel)
                            web.evaluateJavascript("window.FindBridgeGoogleAction.cancel()", null)
                        else
                            repeat(2) {
                                web.evaluateJavascript(
                                    "window.FindBridgeGoogleAction.execute(${JSONObject.quote(ticket)})",
                                    null,
                                )
                            }
                    }
                    assertTrue(checked.await(2, TimeUnit.SECONDS))
                    if (type == GoogleCommandType.MARK_LOST && !cancel) {
                        assertTrue(commitReady.await(5, TimeUnit.SECONDS))
                        val beforeCommit = CountDownLatch(1)
                        scenario.onActivity {
                            web.evaluateJavascript("window.remoteCount") {
                                assertEquals("0", it)
                                beforeCommit.countDown()
                            }
                            repeat(2) {
                                web.evaluateJavascript(
                                    "window.FindBridgeGoogleAction.commit(${JSONObject.quote(ticket)})",
                                    null,
                                )
                            }
                        }
                        assertTrue(beforeCommit.await(2, TimeUnit.SECONDS))
                    }
                    assertTrue(
                        "The synthetic operation must finish",
                        finished.await(5, TimeUnit.SECONDS),
                    )
                    assertEquals(if (cancel) "CANCELED" else "REQUESTED", status)
                }
                val checked = CountDownLatch(1)
                scenario.onActivity {
                    web.evaluateJavascript(
                        "JSON.stringify([window.remoteCount,window.eraseCount,window.capturedContact])"
                    ) {
                        val data = JSONArray(org.json.JSONTokener(it).nextValue().toString())
                        assertEquals(
                            if (cancel || wrongAccount || wrongTarget) 0 else 1,
                            data.getInt(0),
                        )
                        assertEquals(0, data.getInt(1))
                        if (type == GoogleCommandType.MARK_LOST) {
                            assertEquals("+1 202 555 0100", data.getJSONArray(2).getString(0))
                            assertEquals("Fixture message", data.getJSONArray(2).getString(1))
                        }
                        checked.countDown()
                    }
                }
                assertTrue(checked.await(2, TimeUnit.SECONDS))
            }
        } finally {
            GoogleCommandStore.clear()
        }
    }
}
