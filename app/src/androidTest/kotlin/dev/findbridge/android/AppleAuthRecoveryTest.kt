package dev.findbridge.android

import android.view.View
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Synthetic Apple-origin documents only; no owner credentials are loaded or submitted. */
class AppleAuthRecoveryTest {
    @Test
    fun aMissingImageDoesNotAbortSignInButAFailedAuthenticationFrameDoes() {
        val ready = CountDownLatch(1)
        val imageFailed = CountDownLatch(1)
        val frameFailed = CountDownLatch(1)
        ActivityScenario.launch(AuthActivity::class.java).use { scenario ->
            lateinit var web: WebView
            scenario.onActivity { activity ->
                web =
                    AuthActivity::class
                        .java
                        .getDeclaredField("webView")
                        .apply { isAccessible = true }
                        .get(activity) as WebView
                val client = web.webViewClient
                web.webViewClient =
                    object : android.webkit.WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            ready.countDown()
                        }

                        override fun onReceivedError(
                            view: WebView,
                            request: android.webkit.WebResourceRequest,
                            error: android.webkit.WebResourceError,
                        ) {
                            client.onReceivedError(view, request, error)
                            when (request.url.path) {
                                "/appleauth/static/logo.png" -> imageFailed.countDown()
                                "/appleauth/auth/signin" -> frameFailed.countDown()
                            }
                        }
                    }
                web.stopLoading()
                web.settings.blockNetworkLoads = true
                AuthActivity::class
                    .java
                    .getDeclaredMethod("installAutomaticLogin", SavedLogin::class.java)
                    .apply { isAccessible = true }
                    .invoke(
                        activity,
                        SavedLogin("fixture@example.invalid", "public-fixture-password"),
                    )
                web.loadDataWithBaseURL(
                    "https://www.icloud.com/find",
                    "<!doctype html><body></body>",
                    "text/html",
                    "UTF-8",
                    null,
                )
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            scenario.onActivity {
                web.evaluateJavascript(
                    "new Image().src='https://idmsa.apple.com/appleauth/static/logo.png'",
                    null,
                )
            }
            assertTrue(
                "Chromium must report the locally blocked image request",
                imageFailed.await(5, TimeUnit.SECONDS),
            )
            scenario.onActivity { activity ->
                assertEquals(
                    View.VISIBLE,
                    activity.findViewById<View>(AuthActivity.AUTO_STATUS_ID).visibility,
                )
                assertEquals(
                    View.GONE,
                    activity.findViewById<View>(AuthActivity.AUTH_ERROR_ID).visibility,
                )
                web.evaluateJavascript(
                    "(()=>{const f=document.createElement('iframe');f.src='https://idmsa.apple.com/appleauth/auth/signin';document.body.append(f)})()",
                    null,
                )
            }
            assertTrue(
                "The blocked authentication document must report failure immediately",
                frameFailed.await(5, TimeUnit.SECONDS),
            )
            scenario.onActivity { activity ->
                assertEquals(
                    View.GONE,
                    activity.findViewById<View>(AuthActivity.AUTO_STATUS_ID).visibility,
                )
                assertEquals(
                    View.VISIBLE,
                    activity.findViewById<View>(AuthActivity.AUTH_ERROR_ID).visibility,
                )
            }
        }
    }

    @Test
    fun readFailureEndsTheSpinnerAndManualRecoveryDisablesFutureAutomaticInput() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val failed = CountDownLatch(1)
        val manual = CountDownLatch(1)
        val redirected = CountDownLatch(1)
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
                val original = web.webViewClient
                web.webViewClient =
                    object : android.webkit.WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: android.webkit.WebResourceRequest,
                        ): android.webkit.WebResourceResponse {
                            val html =
                                when (request.url.path) {
                                    "/find" ->
                                        """<!doctype html><script>
                                FindBridgeSessionState.postMessage('serviceUnavailable');
                                FixtureFailureDone.postMessage('sent');
                            </script>"""
                                    "/after-failure" ->
                                        """<!doctype html><script>FixtureNavigationDone.postMessage('loaded');</script>"""
                                    "/appleauth/auth/signin" ->
                                        """<!doctype html><input type="email" autocomplete="username"><input type="password" autocomplete="current-password"><button>Continue</button>
                                <script>requestAnimationFrame(()=>requestAnimationFrame(()=>{
                                    FixtureDone.postMessage([...document.querySelectorAll('input')].every(i=>i.value==='')?'empty':'filled');
                                }));</script>"""
                                    else -> ""
                                }
                            return android.webkit.WebResourceResponse(
                                "text/html",
                                "UTF-8",
                                java.io.ByteArrayInputStream(html.toByteArray()),
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
                AuthActivity::class
                    .java
                    .getDeclaredMethod("installAutomaticLogin", SavedLogin::class.java)
                    .apply { isAccessible = true }
                    .invoke(
                        activity,
                        SavedLogin("fixture@example.invalid", "public-fixture-password"),
                    )
                WebViewCompat.addWebMessageListener(
                    web,
                    "FixtureFailureDone",
                    UrlPolicy.loginContainerOrigins,
                ) { _, _, _, _, _ ->
                    failed.countDown()
                }
                web.loadUrl("https://www.icloud.com/find")
            }
            assertTrue(
                "A read failure must immediately replace the infinite spinner",
                failed.await(5, TimeUnit.SECONDS),
            )
            scenario.onActivity { activity ->
                assertEquals(
                    View.GONE,
                    activity.findViewById<View>(AuthActivity.AUTO_STATUS_ID).visibility,
                )
                val errors = activity.findViewById<LinearLayout>(AuthActivity.AUTH_ERROR_ID)
                assertEquals(
                    activity.getString(R.string.connection_failed),
                    (errors.getChildAt(0) as TextView).text,
                )
                WebViewCompat.addWebMessageListener(
                    web,
                    "FixtureNavigationDone",
                    UrlPolicy.loginContainerOrigins,
                ) { _, _, _, _, _ ->
                    redirected.countDown()
                }
                web.loadUrl("https://www.icloud.com/after-failure")
            }
            assertTrue(redirected.await(5, TimeUnit.SECONDS))
            scenario.onActivity { activity ->
                val errors = activity.findViewById<LinearLayout>(AuthActivity.AUTH_ERROR_ID)
                assertEquals(
                    "A page redirect must not dismiss a recovery action before the owner chooses",
                    View.VISIBLE,
                    errors.visibility,
                )
                activity.findViewById<View>(AuthActivity.SHOW_SIGN_IN_ID).performClick()
                assertEquals(View.GONE, errors.visibility)
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO, web.importantForAccessibility)
                WebViewCompat.addWebMessageListener(
                    web,
                    "FixtureDone",
                    UrlPolicy.credentialOrigins,
                ) { _, message, _, _, _ ->
                    assertEquals("empty", message.data)
                    manual.countDown()
                }
                web.loadUrl("https://idmsa.apple.com/appleauth/auth/signin")
            }
            assertTrue(
                "Opening manual sign-in must remove automatic scripts from future documents",
                manual.await(5, TimeUnit.SECONDS),
            )
        }
    }
}
