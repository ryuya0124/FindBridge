package dev.findbridge.android

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewCompat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Intercepted, public dummy documents in a disposable profile; no actual account sign-ins. */
class InlineAppleSignInTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val repository
        get() = AppleRepository.get(context)

    private var originalTransport: Any? = null

    @Before
    fun requireDisposableProfile() {
        assumeTrue(
            InstrumentationRegistry.getArguments().getString("isolatedSessionFixture") == "true"
        )
        assertFalse(repository.hasSession || LoginVault(context).hasSaved)
    }

    @After
    fun cleanup() {
        if (InstrumentationRegistry.getArguments().getString("isolatedSessionFixture") != "true")
            return
        val done = CountDownLatch(1)
        instrumentation.runOnMainSync {
            LoginVault.clearStaged()
            LoginVault(context).clear()
            repository.logout { done.countDown() }
        }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        originalTransport?.let {
            field(AppleRepository::class.java, "transport").set(repository, it)
        }
    }

    @Test
    fun waitingUsesTheNativeListAndAnIndeterminateProgressBar() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val authentication =
                    startFixture(activity, "<!doctype html><body>Waiting fixture</body>")
                assertFalse(authentication.officialVisible)
                assertNativeProgress(activity)
                val nativeRoot = activity.findViewById<View>(MainActivity.MAP_ID).parent as View
                assertTrue(nativeRoot.isShown)
                assertEquals(
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                    authentication.flow.view.importantForAccessibility,
                )
                assertFalse(authentication.flow.view.interactive)
                val content = activity.findViewById<ViewGroup>(android.R.id.content)
                assertSame(authentication.flow.view, content.getChildAt(0))
                assertTrue(
                    activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
                )
                activity.findViewById<View>(InlineSignInView.CANCEL_ID).performClick()
                assertNull(activity.findViewById<View>(InlineSignInView.STATUS_ID))
                assertNull(current(activity))
                assertEquals(
                    0,
                    activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE,
                )
                activity.onChanged()
                assertNull(current(activity))
            }
        }
    }

    @Test
    fun trustedAccountAndPasswordStepsContinueBehindTheNativeList() {
        val done = CountDownLatch(1)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val authentication = startFixture(activity, form)
                WebViewCompat.addWebMessageListener(
                    authentication.flow.webView,
                    "FixtureDone",
                    UrlPolicy.credentialOrigins,
                ) { _, message, _, _, _ ->
                    if (message.data == "done") done.countDown()
                }
            }
            assertTrue(
                "Covered Chromium view must receive trusted, ordered input",
                done.await(15, TimeUnit.SECONDS),
            )
            awaitState(scenario) { it?.flow?.submittedStages == setOf("account", "password") }
            scenario.onActivity { activity ->
                assertNativeProgress(activity)
                val authentication = current(activity)!!
                assertFalse(authentication.officialVisible)
                assertEquals(setOf("account", "password"), authentication.flow.submittedStages)
            }
        }
    }

    @Test
    fun verificationBringsTheOfficialPageForwardAndCancelsAutomaticInput() {
        val loaded = CountDownLatch(1)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val authentication =
                    startFixture(activity, "<!doctype html><input autocomplete='one-time-code'>")
                authentication.flow.webView.webViewClient =
                    fixtureClient(
                        authentication.flow.webView.webViewClient,
                        "<!doctype html><input autocomplete='one-time-code'>",
                        loaded,
                    )
            }
            assertTrue(loaded.await(8, TimeUnit.SECONDS))
            awaitState(scenario) { it?.officialVisible == true }
            scenario.onActivity { activity ->
                val authentication = current(activity)!!
                assertEquals(AppleSignInState.Verification, authentication.state)
                assertTrue(authentication.flow.view.interactive)
                val content = activity.findViewById<ViewGroup>(android.R.id.content)
                assertSame(authentication.flow.view, content.getChildAt(content.childCount - 1))
                assertEquals(
                    View.GONE,
                    activity.findViewById<View>(AuthActivity.AUTO_STATUS_ID).visibility,
                )
                activity.onBackPressed()
                assertNull(current(activity))
            }
        }
    }

    @Test
    fun failureStopsTheSpinnerAndRetryStaysInTheList() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                startFixture(
                    activity,
                    """<!doctype html><script>FindBridgeSessionState.postMessage('serviceUnavailable')</script>""",
                    "https://www.icloud.com/find",
                )
            }
            awaitState(scenario) { it?.state is AppleSignInState.Failed }
            scenario.onActivity { activity ->
                assertNull(activity.findViewById<View>(InlineSignInView.PROGRESS_ID))
                assertFalse(current(activity)!!.officialVisible)
                activity.findViewById<View>(InlineSignInView.RETRY_ID).performClick()
                assertNativeProgress(activity)
                activity.findViewById<View>(InlineSignInView.CANCEL_ID).performClick()
                assertNull(current(activity))
            }
        }
    }

    @Test
    fun acceptedReadResponseReturnsToTheDeviceListWithoutOpeningAnActivity() {
        val response =
            """{"content":[{"id":"inline-fixture","name":"Fixture iPhone","deviceDisplayName":"iPhone","deviceStatus":"203"}]}"""
        val transport = field(AppleRepository::class.java, "transport")
        originalTransport = transport.get(repository)
        transport.set(
            repository,
            AppleTransport(
                connect = { url ->
                    object : HttpURLConnection(url) {
                        override fun connect() {}

                        override fun disconnect() {}

                        override fun usingProxy() = false

                        override fun getResponseCode() = 200

                        override fun getOutputStream() = ByteArrayOutputStream()

                        override fun getInputStream() = ByteArrayInputStream(response.toByteArray())
                    }
                }
            ),
        )
        val envelope =
            JSONObject()
                .put("url", "https://p99881-fmipweb.icloud.com/fmipservice/client/web/initClient")
                .put("body", "{}")
                .put("response", JSONObject(response))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                startFixture(
                    activity,
                    """<!doctype html><script>FindBridgeData.postMessage(${JSONObject.quote(envelope.toString())})</script>""",
                    "https://www.icloud.com/find",
                )
            }
            awaitState(scenario) { it == null && repository.hasSession }
            scenario.onActivity { activity ->
                assertNull(activity.findViewById<View>(InlineSignInView.STATUS_ID))
                assertTrue(texts(activity.window.decorView).any { it.contains("Fixture iPhone") })
                assertTrue(activity.findViewById<View>(MainActivity.MAP_ID).isShown)
            }
        }
    }

    @Test
    fun nativeCredentialsReturnToTheCallerWithoutLaunchingFullScreenAuthentication() {
        val monitor = instrumentation.addMonitor(AuthActivity::class.java.name, null, true)
        try {
            val intent =
                Intent(context, LoginActivity::class.java).putExtra(LoginActivity.INLINE_AUTH, true)
            ActivityScenario.launchActivityForResult<LoginActivity>(intent).use { scenario ->
                scenario.onActivity { activity ->
                    activity.findViewById<TextView>(LoginActivity.ACCOUNT_ID).text =
                        "fixture@example.invalid"
                    activity.findViewById<TextView>(LoginActivity.PASSWORD_ID).text =
                        "fixture-password"
                    activity.findViewById<View>(LoginActivity.CONTINUE_ID).performClick()
                }
                assertEquals(LoginActivity.RESULT_CREDENTIALS_READY, scenario.result.resultCode)
                assertEquals(0, monitor.hits)
                assertNotNull(LoginVault.takeStaged())
            }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test
    fun retryAfterManualVerificationKeepsTheOfficialPageInteractive() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                startFixture(it, "<!doctype html><input autocomplete='one-time-code'>")
            }
            awaitState(scenario) { it?.officialVisible == true }
            scenario.onActivity { activity ->
                val web = current(activity)!!.flow.webView
                web.webViewClient =
                    fixtureClient(
                        web.webViewClient,
                        """<!doctype html><script>FindBridgeSessionState.postMessage('serviceUnavailable')</script>""",
                    )
                web.loadUrl("https://www.icloud.com/find")
            }
            awaitState(scenario) { it?.state is AppleSignInState.Failed }
            scenario.onActivity { activity ->
                val authentication = current(activity)!!
                val web = authentication.flow.webView
                web.webViewClient =
                    fixtureClient(web.webViewClient, "<!doctype html><body>Manual fixture</body>")
                activity.findViewById<View>(InlineSignInView.RETRY_ID).performClick()
                assertEquals(AppleSignInState.Verification, authentication.state)
                assertTrue(authentication.officialVisible)
                assertTrue(authentication.flow.view.interactive)
            }
        }
    }

    @Test
    fun backgroundingPausesTheFlowAndReturningKeepsTheListProgress() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { startFixture(it, "<!doctype html><body>Waiting fixture</body>") }
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            scenario.onActivity { activity ->
                val flow = current(activity)!!.flow
                assertFalse(field(AppleAuthFlow::class.java, "resumed").getBoolean(flow))
            }
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            scenario.onActivity { activity ->
                assertNativeProgress(activity)
                assertTrue(
                    field(AppleAuthFlow::class.java, "resumed").getBoolean(current(activity)!!.flow)
                )
            }
        }
    }

    @Test
    fun recreatingAFailedConnectionKeepsTheErrorWithoutStartingAnotherLogin() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                startFixture(
                    it,
                    """<!doctype html><script>FindBridgeSessionState.postMessage('serviceUnavailable')</script>""",
                    "https://www.icloud.com/find",
                )
            }
            awaitState(scenario) { it?.state is AppleSignInState.Failed }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertNull(current(activity))
                assertNotNull(activity.findViewById<View>(InlineSignInView.RETRY_ID))
                assertNull(activity.findViewById<View>(InlineSignInView.PROGRESS_ID))
                assertTrue(activity.findViewById<View>(MainActivity.MAP_ID).isShown)
            }
        }
    }

    @Test
    fun closingOneSensitiveSurfaceDoesNotUnprotectAnother() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val first = SecureWindowLease.acquire(activity.window)
                val second = SecureWindowLease.acquire(activity.window)
                first.close()
                first.close()
                assertTrue(
                    activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
                )
                second.close()
                assertEquals(
                    0,
                    activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE,
                )
            }
        }
    }

    private fun startFixture(
        activity: MainActivity,
        html: String,
        url: String = "https://idmsa.apple.com/appleauth/auth/signin",
    ): InlineAppleAuthentication {
        LoginVault.stage(SavedLogin("fixture@example.invalid", "fixture-password"))
        MainActivity::class
            .java
            .getDeclaredMethod("startInlineAuthentication")
            .apply { isAccessible = true }
            .invoke(activity)
        return current(activity)!!.also {
            val web = it.flow.webView
            // The staged-login worker has not returned to the UI thread yet. Its initial URL
            // is also intercepted; navigation in these tests never contacts Apple's servers.
            web.webViewClient = fixtureClient(web.webViewClient, html)
            val original = web.webViewClient
            web.webViewClient =
                object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ) = original.shouldInterceptRequest(view, request)

                    override fun onPageStarted(
                        view: WebView,
                        address: String,
                        icon: android.graphics.Bitmap?,
                    ) {
                        original.onPageStarted(view, address, icon)
                        if (address == UrlPolicy.findUrl && url != address) web.loadUrl(url)
                    }

                    override fun onPageFinished(view: WebView, address: String) {
                        original.onPageFinished(view, address)
                    }
                }
        }
    }

    private fun fixtureClient(
        original: WebViewClient,
        html: String,
        loaded: CountDownLatch? = null,
    ) =
        object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(html.toByteArray()))

            override fun onPageStarted(view: WebView, url: String, icon: android.graphics.Bitmap?) {
                original.onPageStarted(view, url, icon)
            }

            override fun onPageFinished(view: WebView, url: String) {
                original.onPageFinished(view, url)
                loaded?.countDown()
            }
        }

    private fun current(activity: MainActivity) =
        field(MainActivity::class.java, "inlineAuthentication").get(activity)
            as? InlineAppleAuthentication

    private fun field(type: Class<*>, name: String) =
        type.getDeclaredField(name).apply { isAccessible = true }

    private fun assertNativeProgress(activity: MainActivity) {
        assertTrue(activity.findViewById<View>(InlineSignInView.STATUS_ID).isShown)
        assertTrue(activity.findViewById<ProgressBar>(InlineSignInView.PROGRESS_ID).isIndeterminate)
        assertTrue(activity.findViewById<View>(InlineSignInView.SPINNER_ID).isShown)
        assertTrue(activity.findViewById<View>(MainActivity.MAP_ID).isShown)
    }

    private fun texts(view: View): List<String> =
        when (view) {
            is TextView -> listOf(view.text.toString())
            is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
            else -> emptyList()
        }

    private fun awaitState(
        scenario: ActivityScenario<MainActivity>,
        predicate: (InlineAppleAuthentication?) -> Boolean,
    ) {
        val end = android.os.SystemClock.uptimeMillis() + 10_000
        var ready = false
        while (!ready && android.os.SystemClock.uptimeMillis() < end) {
            scenario.onActivity { ready = predicate(current(it)) }
            if (!ready) android.os.SystemClock.sleep(30)
        }
        assertTrue("Expected inline authentication state was not reached", ready)
    }

    private val form =
        """
        <!doctype html><meta name="viewport" content="width=device-width,initial-scale=1">
        <input type="email" autocomplete="username"><input type="password" autocomplete="current-password" style="display:none">
        <button>Continue</button><script>let step=0;document.querySelector('button').onclick=e=>{
          if(!e.isTrusted)return;
          if(step===0&&document.querySelector('input[type=email]').value==='fixture@example.invalid'){
            step=1;document.querySelector('input[type=email]').readOnly=true;
            document.querySelector('input[type=password]').style.display='block';
          }else if(step===1&&document.querySelector('input[type=password]').value==='fixture-password')FixtureDone.postMessage('done');
        };</script>
        """
            .trimIndent()
}
