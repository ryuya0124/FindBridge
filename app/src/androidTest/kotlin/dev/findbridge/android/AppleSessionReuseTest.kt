package dev.findbridge.android

import android.webkit.CookieManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Run only in a disposable Android user. Every HTTP connection below is an in-memory fixture. */
class AppleSessionReuseTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val address = "https://p99881-fmipweb.icloud.com/fmipservice/client/web/refreshClient"

    @Before
    fun requireDisposableProfile() {
        assumeTrue(
            InstrumentationRegistry.getArguments().getString("isolatedSessionFixture") == "true"
        )
        assumeFalse(AppleRepository.get(context).hasSession || LoginVault(context).hasSaved)
    }

    @Test
    fun nativeRefreshCarriesRotatedCookiesIntoTheNextRequestWithoutSigningIn() {
        onMain {
            CookieManager.getInstance()
                .setCookie(address, "bridge_fixture=old; Path=/; Secure; HttpOnly")
            CookieManager.getInstance()
                .setCookie(address, "bridge_trust=keep; Path=/; Secure; Max-Age=3600")
        }
        val first = Response(address, 200, listOf("bridge_fixture=new; Path=/; Secure; HttpOnly"))
        val second = Response(address, 200)
        val responses = java.util.ArrayDeque(listOf(first, second))
        val transport = AppleTransport(connect = { responses.removeFirst() })
        val snapshot = snapshot("bridge_fixture=old; bridge_trust=keep")
        assertNotNull(
            transport.send(snapshot, AppleRequestKind.READ) { true }.optJSONArray("content")
        )
        assertTrue(snapshot.getString("cookie").contains("bridge_fixture=new"))
        assertTrue(snapshot.getString("cookie").contains("bridge_trust=keep"))
        transport.send(snapshot, AppleRequestKind.READ) { true }
        assertTrue(second.getRequestProperty("Cookie").contains("bridge_fixture=new"))
        assertFalse(second.getRequestProperty("Cookie").contains("bridge_fixture=old"))
        assertTrue(first.closed && second.closed)
    }

    @Test
    fun incompleteBrowserJarDoesNotDiscardTheEncryptedNativeCredentials() {
        val previous = "bridge_legacy_fixture=keep"
        val updated =
            AppleSessionCookies()
                .update(
                    address,
                    previous,
                    listOf("bridge_new_fixture=value; Path=/; Secure"),
                    { true },
                )
                .get(5, TimeUnit.SECONDS)
        assertEquals(previous, updated)
    }

    @Test
    fun obsoleteAccountCannotApplyResponseCookies() {
        val updated =
            AppleSessionCookies()
                .update(
                    address,
                    "",
                    listOf("bridge_obsolete_fixture=value; Path=/; Secure"),
                    { false },
                )
                .get(5, TimeUnit.SECONDS)
        assertNull(updated)
        onMain {
            assertFalse(
                CookieManager.getInstance()
                    .getCookie(address)
                    .orEmpty()
                    .contains("bridge_obsolete_fixture=")
            )
        }
    }

    @Test
    fun permissionDenialDoesNotBecomeAnotherPasswordAuthentication() {
        val denied = Response(address, 403)
        val transport = AppleTransport(connect = { denied })
        assertThrows(IllegalStateException::class.java) {
            transport.send(snapshot(""), AppleRequestKind.READ) { true }
        }
        assertTrue(denied.closed)
        assertEquals(
            LostModeResult.REJECTED,
            LostMode.result(JSONObject().put("statusCode", "403"), "fixture"),
        )
        assertEquals(
            AppleNotification.Result.FAILED,
            AppleNotification.result(JSONObject().put("statusCode", "403"), "fixture", true),
        )
    }

    @Test
    fun expiredResponsesAreDistinctFromNetworkAndPermissionFailures() {
        for (status in listOf(401, 421, 450)) {
            val response = Response(address, status)
            assertThrows(AppleAuthenticationExpiredException::class.java) {
                AppleTransport(connect = { response }).send(snapshot(""), AppleRequestKind.READ) {
                    true
                }
            }
            assertTrue(response.closed)
        }
        val response = Response(address, 200, body = """{"statusCode":"450"}""")
        assertThrows(AppleAuthenticationExpiredException::class.java) {
            AppleTransport(connect = { response }).send(snapshot(""), AppleRequestKind.READ) {
                true
            }
        }
    }

    @Test
    fun reconnectWithAValidSessionKeepsBrowserTrustAndDoesNotOpenAuthentication() {
        val repository = AppleRepository.get(context)
        val vault = LoginVault(context)
        val session =
            AppleRepository::class.java.getDeclaredField("session").apply { isAccessible = true }
        val loading =
            AppleRepository::class.java.getDeclaredField("loading").apply { isAccessible = true }
        val oldSession = session.get(repository)
        val oldLoading = loading.getBoolean(repository)
        val auth = instrumentation.addMonitor(AuthActivity::class.java.name, null, true)
        val form = instrumentation.addMonitor(LoginActivity::class.java.name, null, true)
        try {
            onMain {
                session.set(repository, snapshot("bridge_fixture=fixture"))
                // A native refresh already in progress must not be replaced by a fresh login.
                loading.setBoolean(repository, true)
                CookieManager.getInstance()
                    .setCookie(address, "bridge_reconnect_trust=keep; Path=/; Secure; Max-Age=3600")
            }
            vault.save(SavedLogin("fixture@example.invalid", "public-fixture-password"), true)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    AppleConnectionController(activity, repository, vault) { true }.reconnect()
                    assertTrue(repository.hasSession)
                    assertTrue(
                        CookieManager.getInstance()
                            .getCookie(address)
                            .orEmpty()
                            .contains("bridge_reconnect_trust=keep")
                    )
                }
            }
            assertEquals(0, auth.hits)
            assertEquals(0, form.hits)
        } finally {
            instrumentation.removeMonitor(auth)
            instrumentation.removeMonitor(form)
            vault.clear()
            LoginVault.clearStaged()
            onMain {
                session.set(repository, oldSession)
                loading.setBoolean(repository, oldLoading)
            }
        }
    }

    @Test
    fun submittingUnchangedSavedCredentialsKeepsTheVerifiedSession() {
        val repository = AppleRepository.get(context)
        val vault = LoginVault(context)
        val session =
            AppleRepository::class.java.getDeclaredField("session").apply { isAccessible = true }
        val verified =
            AppleRepository::class.java.getDeclaredField("nativeTransportVerified").apply {
                isAccessible = true
            }
        val oldSession = session.get(repository)
        val oldVerified = verified.getBoolean(repository)
        val auth = instrumentation.addMonitor(AuthActivity::class.java.name, null, true)
        try {
            onMain {
                session.set(repository, snapshot("bridge_unchanged=keep"))
                verified.setBoolean(repository, true)
                CookieManager.getInstance()
                    .setCookie(address, "bridge_unchanged=keep; Path=/; Secure")
            }
            vault.save(SavedLogin("fixture@example.invalid", "public-fixture-password"), true)
            ActivityScenario.launch(LoginActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    // Populate only public fixture values; no owner profile is used in this suite.
                    LoginActivity::class
                        .java
                        .getDeclaredField("existing")
                        .apply { isAccessible = true }
                        .set(
                            activity,
                            SavedLogin("fixture@example.invalid", "public-fixture-password"),
                        )
                    activity
                        .findViewById<android.widget.EditText>(LoginActivity.ACCOUNT_ID)
                        .setText("fixture@example.invalid")
                    activity
                        .findViewById<android.view.View>(LoginActivity.CONTINUE_ID)
                        .performClick()
                }
                val deadline = android.os.SystemClock.uptimeMillis() + 5000
                while (
                    scenario.state != androidx.lifecycle.Lifecycle.State.DESTROYED &&
                        android.os.SystemClock.uptimeMillis() < deadline
                ) {
                    instrumentation.waitForIdleSync()
                    android.os.SystemClock.sleep(20)
                }
                assertEquals(androidx.lifecycle.Lifecycle.State.DESTROYED, scenario.state)
            }
            assertEquals(0, auth.hits)
            assertTrue(repository.hasSession)
            onMain {
                assertTrue(
                    CookieManager.getInstance()
                        .getCookie(address)
                        .orEmpty()
                        .contains("bridge_unchanged=keep")
                )
            }
        } finally {
            instrumentation.removeMonitor(auth)
            vault.clear()
            LoginVault.clearStaged()
            onMain {
                session.set(repository, oldSession)
                verified.setBoolean(repository, oldVerified)
            }
        }
    }

    private fun snapshot(cookie: String) =
        JSONObject()
            .put("url", address)
            .put("cookie", cookie)
            .put("body", JSONObject().put("clientContext", JSONObject()))
            .put("userAgent", "FindBridge offline fixture")

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private class Response(
        address: String,
        private val status: Int,
        private val cookies: List<String> = emptyList(),
        private val body: String = """{"content":[]}""",
    ) : HttpURLConnection(URL(address)) {
        var closed = false
            private set

        override fun connect() = Unit

        override fun disconnect() {
            closed = true
        }

        override fun usingProxy() = false

        override fun getResponseCode() = status

        override fun getOutputStream() = ByteArrayOutputStream()

        override fun getInputStream() = ByteArrayInputStream(body.toByteArray())

        override fun getHeaderFields(): MutableMap<String, MutableList<String>> =
            mutableMapOf("Set-Cookie" to cookies.toMutableList())
    }
}
