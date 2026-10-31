package dev.findbridge.android

import android.webkit.WebView
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** A local document and synthetic fetch response; never connects to an Apple service. */
class AppleSessionObserverTest {
    @Test
    fun requestObjectsKeepTheirBodyAndTheOriginalFetchPromise() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val script = context.assets.open("apple-session.js").bufferedReader().use { it.readText() }
        val completed = CountDownLatch(2)
        var response = ""
        var promise = ""
        lateinit var web: WebView
        instrumentation.runOnMainSync {
            web = WebView(context)
            web.settings.javaScriptEnabled = true
            WebViewCompat.addWebMessageListener(
                web,
                "FindBridgeData",
                setOf("https://www.icloud.com"),
            ) { _, message, _, _, _ ->
                response = message.data.orEmpty()
                completed.countDown()
            }
            WebViewCompat.addWebMessageListener(
                web,
                "FixtureDone",
                setOf("https://www.icloud.com"),
            ) { _, message, _, _, _ ->
                promise = message.data.orEmpty()
                completed.countDown()
            }
            web.loadDataWithBaseURL(
                "https://www.icloud.com",
                """
                <!doctype html><script>
                window.fetch=()=>{window.originalPromise=Promise.resolve(new Response('{"content":[]}'));return window.originalPromise;};
                $script
                const request=new Request('https://p99881-fmipweb.icloud.com/fmipservice/client/web/refreshClient',
                  {method:'POST',body:JSON.stringify({clientContext:{fixture:'body'}})});
                const pending=fetch(request);
                FixtureDone.postMessage(pending===window.originalPromise?'preserved':'replaced');
                </script>
            """
                    .trimIndent(),
                "text/html",
                "UTF-8",
                null,
            )
        }
        try {
            assertTrue(completed.await(5, TimeUnit.SECONDS))
            assertEquals("preserved", promise)
            assertEquals(
                "body",
                org.json
                    .JSONObject(org.json.JSONObject(response).getString("body"))
                    .getJSONObject("clientContext")
                    .getString("fixture"),
            )
        } finally {
            instrumentation.runOnMainSync { web.destroy() }
        }
    }

    @Test
    fun expiredHttpAndJsonResponsesRequestAuthenticationButPermissionDenialsDoNot() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
        val script = context.assets.open("apple-session.js").bufferedReader().use { it.readText() }
        val done = CountDownLatch(1)
        val messages = java.util.concurrent.CopyOnWriteArrayList<String>()
        lateinit var web: WebView
        instrumentation.runOnMainSync {
            web = WebView(context)
            web.settings.javaScriptEnabled = true
            WebViewCompat.addWebMessageListener(
                web,
                "FindBridgeSessionState",
                setOf("https://www.icloud.com"),
            ) { _, message, _, mainFrame, _ ->
                if (mainFrame) messages += message.data.orEmpty()
            }
            WebViewCompat.addWebMessageListener(
                web,
                "FixtureDone",
                setOf("https://www.icloud.com"),
            ) { _, _, _, mainFrame, _ ->
                if (mainFrame) done.countDown()
            }
            web.loadDataWithBaseURL(
                "https://www.icloud.com",
                """
                <!doctype html><script>
                let nextStatus=200, nextBody='{}';
                window.fetch=async()=>new Response(nextBody,{status:nextStatus});
                $script
                (async()=>{
                    const url='https://p99881-fmipweb.icloud.com/fmipservice/client/web/refreshClient';
                    for(const status of [401,421,450,403]) {
                        nextStatus=status; await fetch(url);
                    }
                    nextStatus=200; nextBody='{"statusCode":"450"}'; await fetch(url);
                    // Wait for cloned-response readers using an actual event, without a timer.
                    nextBody='{}'; await fetch('https://www.icloud.com/ping');
                    await new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)));
                    FixtureDone.postMessage('done');
                })();
                </script>
            """
                    .trimIndent(),
                "text/html",
                "UTF-8",
                null,
            )
        }
        try {
            assertTrue("Synthetic observer must complete", done.await(5, TimeUnit.SECONDS))
            assertEquals(4, messages.count { it == "authenticationRequired" })
            assertEquals(1, messages.count { it == "serviceUnavailable" })
        } finally {
            instrumentation.runOnMainSync { web.destroy() }
        }
    }

    @Test
    fun embeddedFindAppCapturesUrlObjectsAndRequestBodies() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val script = context.assets.open("apple-session.js").bufferedReader().use { it.readText() }
        val received = CountDownLatch(1)
        val responses = java.util.concurrent.CopyOnWriteArrayList<String>()
        lateinit var web: WebView
        instrumentation.runOnMainSync {
            web = WebView(context)
            web.settings.javaScriptEnabled = true
            assertTrue(
                AppleSessionObserver.install(
                    web,
                    script,
                    {
                        responses += it
                        received.countDown()
                    },
                    { fail("A successful read must not fail: $it") },
                )
            )
            web.webViewClient =
                object : android.webkit.WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: android.webkit.WebResourceRequest,
                    ): android.webkit.WebResourceResponse {
                        val content =
                            when {
                                request.url.host.orEmpty().startsWith("p99881-") ->
                                    """{"content":[],"serverContext":{}}"""
                                request.url.path == "/find" ->
                                    """<!doctype html><iframe src="https://www.icloud.com/embedded-find"></iframe>"""
                                else ->
                                    """<!doctype html><script>
                            fetch(new URL('https://p99881-fmipweb.icloud.com/fmipservice/client/web/refreshClient'),
                              {method:'POST',body:JSON.stringify({clientContext:{fmly:true}})});
                        </script>"""
                            }
                        return android.webkit.WebResourceResponse(
                            if (request.url.host.orEmpty().startsWith("p99881-")) "application/json"
                            else "text/html",
                            "UTF-8",
                            200,
                            "OK",
                            mapOf("Access-Control-Allow-Origin" to "*"),
                            java.io.ByteArrayInputStream(content.toByteArray()),
                        )
                    }
                }
            web.loadUrl("https://www.icloud.com/find")
        }
        try {
            assertTrue(
                "An iCloud iframe response must complete without waiting for the cover deadline",
                received.await(5, TimeUnit.SECONDS),
            )
            val envelope = org.json.JSONObject(responses.single())
            assertEquals(0, envelope.getJSONObject("response").getJSONArray("content").length())
            assertTrue(
                org.json
                    .JSONObject(envelope.getString("body"))
                    .getJSONObject("clientContext")
                    .getBoolean("fmly")
            )
        } finally {
            instrumentation.runOnMainSync { web.destroy() }
        }
    }

    @Test
    fun anICloudFrameCannotSupplyASessionToAForeignTopPage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val attempted = CountDownLatch(1)
        val responses = java.util.concurrent.CopyOnWriteArrayList<String>()
        lateinit var web: WebView
        instrumentation.runOnMainSync {
            web = WebView(context)
            web.settings.javaScriptEnabled = true
            assertTrue(
                AppleSessionObserver.install(web, "", { responses += it }, { responses += it })
            )
            WebViewCompat.addWebMessageListener(
                web,
                "FixtureDone",
                setOf("https://www.icloud.com"),
            ) { _, _, _, _, _ ->
                attempted.countDown()
            }
            web.webViewClient =
                object : android.webkit.WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: android.webkit.WebResourceRequest,
                    ): android.webkit.WebResourceResponse {
                        val html =
                            if (request.url.host == "outside.invalid")
                                """<!doctype html><iframe src="https://www.icloud.com/frame"></iframe>"""
                            else
                                """<!doctype html><script>
                        FindBridgeData.postMessage('{"url":"https://p99881-fmipweb.icloud.com/fmipservice/client/web/refreshClient","response":{"content":[]}}');
                        FindBridgeSessionState.postMessage('serviceUnavailable');
                        FixtureDone.postMessage('attempted');
                    </script>"""
                        return android.webkit.WebResourceResponse(
                            "text/html",
                            "UTF-8",
                            java.io.ByteArrayInputStream(html.toByteArray()),
                        )
                    }
                }
            web.loadUrl("https://outside.invalid/")
        }
        try {
            assertTrue(attempted.await(5, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            assertTrue("Origin and top-page validation must both hold", responses.isEmpty())
        } finally {
            instrumentation.runOnMainSync { web.destroy() }
        }
    }
}
