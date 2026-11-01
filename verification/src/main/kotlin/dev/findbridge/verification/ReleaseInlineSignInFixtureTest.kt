package dev.findbridge.verification

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ProgressBar
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** The actual minified APK, an empty emulator, and intercepted public dummy documents only. */
@SuppressLint("ResourceType", "SetJavaScriptEnabled")
class ReleaseInlineSignInFixtureTest {
    @Test
    fun signedApkAuthenticatesBehindTheNativeListAndCancelsInPlace() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(
            InstrumentationRegistry.getArguments().getString("isolatedReleaseFixture") == "true"
        )
        val context = instrumentation.targetContext
        assertFalse(
            context
                .getSharedPreferences("apple_credentials_policy", Context.MODE_PRIVATE)
                .getBoolean("saved", false)
        )
        assertFalse(
            context.getSharedPreferences("apple_session", Context.MODE_PRIVATE).contains("data")
        )
        val completed = CountDownLatch(1)
        val main =
            instrumentation.startActivitySync(
                Intent()
                    .setClassName(
                        context.packageName,
                        "dev.findbridge.android.MainActivity",
                    )
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        val content = main.findViewById<ViewGroup>(android.R.id.content)
        var browser: WebView? = null
        var intercepted: WebView? = null
        val originalCallback = main.window.callback
        val loginMonitor =
            instrumentation.addMonitor("dev.findbridge.android.LoginActivity", null, false)
        try {
            instrumentation.runOnMainSync {
                content.setOnHierarchyChangeListener(
                    object : ViewGroup.OnHierarchyChangeListener {
                        override fun onChildViewAdded(parent: View, child: View) {
                            findWebView(child)?.let {
                                // This runs while the host is attached, before the engine starts.
                                // Network stays blocked until every request has a local
                                // interceptor.
                                it.settings.blockNetworkLoads = true
                                browser = it
                            }
                        }

                        override fun onChildViewRemoved(parent: View, child: View) {}
                    }
                )
                main.window.callback =
                    object : Window.Callback by originalCallback {
                        override fun onWindowFocusChanged(hasFocus: Boolean) {
                            originalCallback.onWindowFocusChanged(hasFocus)
                            val web = browser ?: return
                            if (web === intercepted) return
                            intercepted = web
                            val original = web.webViewClient
                            web.addJavascriptInterface(
                                object {
                                    @JavascriptInterface
                                    fun complete() {
                                        completed.countDown()
                                    }
                                },
                                "NativeFixture",
                            )
                            web.webViewClient =
                                object : WebViewClient() {
                                    override fun shouldInterceptRequest(
                                        view: WebView,
                                        request: WebResourceRequest,
                                    ): WebResourceResponse {
                                        val html =
                                            if (request.url.host == "www.icloud.com") container
                                            else form
                                        return WebResourceResponse(
                                            "text/html",
                                            "UTF-8",
                                            ByteArrayInputStream(html.toByteArray()),
                                        )
                                    }

                                    override fun onPageStarted(
                                        view: WebView,
                                        url: String,
                                        icon: android.graphics.Bitmap?,
                                    ) {
                                        original.onPageStarted(view, url, icon)
                                    }

                                    override fun onPageFinished(view: WebView, url: String) {
                                        original.onPageFinished(view, url)
                                    }
                                }
                            web.settings.blockNetworkLoads = false
                            main.findViewById<View>(RETRY)?.performClick()
                        }
                    }
                main.findViewById<View>(1001).performClick()
            }
            val login = instrumentation.waitForMonitorWithTimeout(loginMonitor, 10_000)
            assertNotNull("Native credential form must open", login)
            instrumentation.runOnMainSync {
                descendants(login.window.decorView)
                    .filterIsInstance<CompoundButton>()
                    .first()
                    .isChecked = false
                login.findViewById<EditText>(1101).setText("fixture@example.invalid")
                login.findViewById<EditText>(1102).setText("fixture-password")
                login.findViewById<View>(1103).performClick()
            }
            assertTrue(
                "The signed engine must complete both trusted input stages under the list",
                completed.await(25, TimeUnit.SECONDS),
            )
            instrumentation.runOnMainSync {
                assertSame(browser, intercepted)
                assertTrue(main.findViewById<View>(STATUS).isShown)
                assertTrue(main.findViewById<ProgressBar>(PROGRESS).isIndeterminate)
                assertTrue(main.findViewById<View>(1003).isShown)
                assertTrue(main.findViewById<View>(1008).isShown)
                assertTrue(
                    main.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
                )
                browser!!.evaluateJavascript(
                    "FindBridgeSessionState.postMessage('serviceUnavailable')",
                    null,
                )
            }
            await { main.findViewById<View>(RETRY)?.isShown == true }
            instrumentation.runOnMainSync {
                assertNull(main.findViewById<View>(PROGRESS))
                main.findViewById<View>(CANCEL).performClick()
                assertNull(main.findViewById<View>(STATUS))
                assertEquals(
                    0,
                    main.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE,
                )
            }
        } finally {
            instrumentation.removeMonitor(loginMonitor)
            instrumentation.runOnMainSync {
                content.setOnHierarchyChangeListener(null)
                main.window.callback = originalCallback
                main.finish()
            }
        }
    }

    private fun await(predicate: () -> Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val end = android.os.SystemClock.uptimeMillis() + 8_000
        var ready = false
        while (!ready && android.os.SystemClock.uptimeMillis() < end) {
            instrumentation.runOnMainSync { ready = predicate() }
            if (!ready) android.os.SystemClock.sleep(30)
        }
        assertTrue("Expected native state was not reached", ready)
    }

    private fun descendants(view: View): List<View> =
        listOf(view) +
            if (view is ViewGroup)
                (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
            else emptyList()

    private fun findWebView(view: View) =
        descendants(view).filterIsInstance<WebView>().firstOrNull()

    companion object {
        private const val STATUS = 0x16fb0160
        private const val PROGRESS = 0x16fb0162
        private const val CANCEL = 0x16fb0163
        private const val RETRY = 0x16fb0164
        private val container =
            """
            <!doctype html><meta name="viewport" content="width=device-width,initial-scale=1">
            <iframe src="https://idmsa.apple.com/appleauth/auth/signin" style="width:95%;height:620px"></iframe>
            <script>addEventListener('message',e=>{if(e.origin==='https://idmsa.apple.com'&&e.data==='fixture-done')NativeFixture.complete()})</script>
            """
                .trimIndent()
        private val form =
            """
            <!doctype html><meta name="viewport" content="width=device-width,initial-scale=1">
            <input type="email" autocomplete="username"><input type="password" autocomplete="current-password" style="display:none">
            <button>Continue</button><script>let step=0;document.querySelector('button').onclick=e=>{
              if(!e.isTrusted)return;
              if(step===0&&document.querySelector('input[type=email]').value==='fixture@example.invalid'){
                step=1;document.querySelector('input[type=email]').readOnly=true;
                document.querySelector('input[type=password]').style.display='block';
              }else if(step===1&&document.querySelector('input[type=password]').value==='fixture-password')parent.postMessage('fixture-done','https://www.icloud.com');
            };</script>
            """
                .trimIndent()
    }
}
