package dev.findbridge.android

import android.os.Looper
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.TimeUnit
import org.json.JSONObject

internal enum class AppleRequestKind {
    READ,
    SOUND,
    LOST_MODE,
    NOTIFICATION,
}

internal class AppleAuthenticationExpiredException : Exception()

/** Native Apple requests, endpoint boundaries and cookie rotation; call on a worker thread. */
internal class AppleTransport(
    private val connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val cookies: AppleSessionCookies = AppleSessionCookies(),
) {
    fun send(snapshot: JSONObject, kind: AppleRequestKind, isCurrent: () -> Boolean): JSONObject {
        check(Looper.myLooper() != Looper.getMainLooper())
        check(isCurrent())
        val address = snapshot.getString("url")
        require(
            when (kind) {
                AppleRequestKind.READ -> UrlPolicy.isReadEndpoint(address)
                AppleRequestKind.SOUND -> UrlPolicy.isSoundEndpoint(address)
                AppleRequestKind.LOST_MODE -> UrlPolicy.isLostModeEndpoint(address)
                AppleRequestKind.NOTIFICATION -> UrlPolicy.isNotificationEndpoint(address)
            }
        )
        val connection = connect(URI(address).toURL())
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "text/plain;charset=UTF-8")
        connection.setRequestProperty("Origin", "https://www.icloud.com")
        connection.setRequestProperty("Referer", "https://www.icloud.com/")
        connection.setRequestProperty("User-Agent", snapshot.optString("userAgent"))
        connection.setRequestProperty("Cookie", snapshot.optString("cookie"))
        connection.setRequestProperty("Accept", "application/json")
        connection.doOutput = true
        try {
            connection.outputStream.use {
                it.write(snapshot.getJSONObject("body").toString().toByteArray(Charsets.UTF_8))
            }
            when (connection.responseCode) {
                200 -> Unit
                401,
                421,
                450 -> throw AppleAuthenticationExpiredException()
                // A denied operation is not proof that the account needs another sign-in.
                else -> error("Apple service unavailable")
            }
            val buffer = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val chunk = ByteArray(8192)
                while (true) {
                    val count = input.read(chunk)
                    if (count == -1) break
                    buffer.write(chunk, 0, count)
                    check(buffer.size() <= RESPONSE_LIMIT)
                }
            }
            check(isCurrent())
            val response =
                if (kind == AppleRequestKind.SOUND && buffer.size() == 0) JSONObject()
                else JSONObject(buffer.toString(Charsets.UTF_8.name()))
            if (response.optString("statusCode") in setOf("401", "421", "450"))
                throw AppleAuthenticationExpiredException()
            val updates =
                connection.headerFields.entries
                    .filter { it.key.equals("Set-Cookie", true) }
                    .flatMap { it.value.orEmpty() }
            if (updates.isNotEmpty()) {
                // Completion callbacks progress immediately; this is only a failure deadline.
                runCatching {
                    cookies
                        .update(address, snapshot.optString("cookie"), updates, isCurrent)
                        .get(5, TimeUnit.SECONDS)
                }
                    .getOrNull()
                    ?.let { snapshot.put("cookie", it) }
            }
            check(isCurrent())
            return response
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val RESPONSE_LIMIT = 4_000_000
    }
}
