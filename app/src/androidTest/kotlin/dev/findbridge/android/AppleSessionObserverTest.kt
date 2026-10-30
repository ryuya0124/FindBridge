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
                    nextBody='{}'; await fetch(url);
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
            assertEquals(List(4) { "authenticationRequired" }, messages.toList())
        } finally {
            instrumentation.runOnMainSync { web.destroy() }
        }
    }
}
