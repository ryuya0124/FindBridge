package dev.findbridge.android

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ActivityScenario
import androidx.webkit.WebViewCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class GoogleAuthStateTest {
    @Test
    fun hiddenFieldsDoNotRevealThePageButVisibleVerificationDoes() {
        val verified = CountDownLatch(1)
        val checkedHidden = CountDownLatch(1)
        ActivityScenario.launch(AuthActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val web =
                    AuthActivity::class
                        .java
                        .getDeclaredField("webView")
                        .apply { isAccessible = true }
                        .get(activity) as WebView
                web.stopLoading()
                val origins = setOf("https://accounts.google.com")
                WebViewCompat.addWebMessageListener(web, "FindBridgeGoogleAuthState", origins) {
                    _,
                    message,
                    origin,
                    mainFrame,
                    _ ->
                    assertTrue(mainFrame)
                    assertEquals("https://accounts.google.com", origin.toString().trimEnd('/'))
                    assertEquals("verification", message.data)
                    verified.countDown()
                }
                WebViewCompat.addDocumentStartJavaScript(
                    web,
                    activity.assets.open("google-auth-state.js").bufferedReader().use {
                        it.readText()
                    },
                    origins,
                )
                web.webViewClient =
                    object : WebViewClient() {
                        private var checked = false

                        override fun onPageFinished(view: WebView, url: String) {
                            if (
                                checked || url != "https://accounts.google.com/v3/signin/identifier"
                            )
                                return
                            checked = true
                            web.evaluateJavascript("document.readyState") {
                                assertEquals(
                                    "Hidden form fields are not an owner verification",
                                    1L,
                                    verified.count,
                                )
                                checkedHidden.countDown()
                                web.evaluateJavascript(
                                    "document.getElementById('fixture').hidden=false",
                                    null,
                                )
                            }
                        }
                    }
                web.loadDataWithBaseURL(
                    "https://accounts.google.com/v3/signin/identifier",
                    "<!doctype html><body><input type=hidden><input id=fixture hidden autocomplete=one-time-code></body>",
                    "text/html",
                    "UTF-8",
                    null,
                )
            }
            assertTrue(checkedHidden.await(5, TimeUnit.SECONDS))
            assertTrue(
                "A visible verification step must reveal the official page immediately",
                verified.await(5, TimeUnit.SECONDS),
            )
        }
    }
}
