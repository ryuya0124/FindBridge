package dev.findbridge.verification

import android.content.Context
import android.os.Bundle
import android.webkit.CookieManager
import androidx.test.platform.app.InstrumentationRegistry
import java.net.HttpURLConnection
import java.net.URI
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Reads the current signed APK's own encrypted session, and reports only status booleans/codes. */
class AppleTransportProbeTest {
    @Test
    fun checksNativeRefreshWithoutChangingLoginPolicy() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("appletransport") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val type = Class.forName(args.getString("repoClass")!!, true, context.classLoader)
        val repo =
            type
                .getDeclaredConstructor(Context::class.java)
                .apply { isAccessible = true }
                .newInstance(context)
        val session =
            type
                .getDeclaredField(args.getString("sessionField")!!)
                .apply { isAccessible = true }
                .get(repo) as? JSONObject
        assertNotNull("An encrypted Apple session must exist", session)
        val snapshot = session!!
        val address = URI(snapshot.getString("url"))
        check(
            address.scheme == "https" &&
                address.host.endsWith(".icloud.com") &&
                address.path.endsWith("/refreshClient")
        )
        var currentCookie = ""
        instrumentation.runOnMainSync {
            currentCookie = CookieManager.getInstance().getCookie(address.toString()).orEmpty()
        }
        val outcome =
            JSONObject()
                .put("savedCookiePresent", snapshot.optString("cookie").isNotEmpty())
                .put("webCookiePresent", currentCookie.isNotEmpty())
                .put("savedCookieMatchesWeb", currentCookie == snapshot.optString("cookie"))
        fun request(cookie: String): JSONObject {
            val connection = address.toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15000
            connection.readTimeout = 20000
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "text/plain;charset=UTF-8")
            connection.setRequestProperty("Origin", "https://www.icloud.com")
            connection.setRequestProperty("Referer", "https://www.icloud.com/")
            connection.setRequestProperty("User-Agent", snapshot.optString("userAgent"))
            connection.setRequestProperty("Cookie", cookie)
            connection.setRequestProperty("Accept", "application/json")
            return try {
                connection.outputStream.use {
                    it.write(snapshot.getJSONObject("body").toString().toByteArray())
                }
                val status = connection.responseCode
                val stream =
                    if (status in 200..299) connection.inputStream else connection.errorStream
                val bytes = stream?.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(4096)
                    while (output.size() < 200_000) {
                        val count = input.read(chunk, 0, minOf(chunk.size, 200_000 - output.size()))
                        if (count < 0) break
                        output.write(chunk, 0, count)
                    }
                    output.toByteArray()
                }
                val response = runCatching {
                    JSONObject(bytes?.toString(Charsets.UTF_8).orEmpty())
                }
                    .getOrNull()
                JSONObject()
                    .put("httpStatus", status)
                    .put(
                        "hasSetCookie",
                        connection.headerFields.keys.any { it.equals("Set-Cookie", true) },
                    )
                    .put("hasContent", response?.optJSONArray("content") != null)
                    .put("serviceStatus", response?.optInt("statusCode", -1) ?: -1)
            } finally {
                connection.disconnect()
            }
        }
        outcome.put("saved", request(snapshot.optString("cookie")))
        if (currentCookie.isNotEmpty() && currentCookie != snapshot.optString("cookie"))
            outcome.put("current", request(currentCookie))
        instrumentation.sendStatus(
            0,
            Bundle().apply { putString("appleTransport", outcome.toString()) },
        )
    }
}
