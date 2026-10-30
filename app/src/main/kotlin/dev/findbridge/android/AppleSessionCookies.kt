package dev.findbridge.android

import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import java.util.concurrent.CompletableFuture

/** Receives cookie rotations in the same Apple profile that performed authentication. */
internal class AppleSessionCookies {
    private val main = Handler(Looper.getMainLooper())

    fun update(
        url: String,
        previous: String,
        headers: List<String>,
        isCurrent: () -> Boolean,
    ): CompletableFuture<String?> {
        val result = CompletableFuture<String?>()
        val allowed =
            UrlPolicy.isReadEndpoint(url) ||
                UrlPolicy.isSoundEndpoint(url) ||
                UrlPolicy.isLostModeEndpoint(url) ||
                UrlPolicy.isNotificationEndpoint(url)
        if (
            !allowed ||
                headers.size > 64 ||
                headers.any { it.length > 16_384 || '\r' in it || '\n' in it }
        ) {
            result.complete(null)
            return result
        }
        main.post {
            if (!isCurrent()) {
                result.complete(null)
                return@post
            }
            val cookies = CookieManager.getInstance()
            if (headers.isEmpty()) {
                result.complete(previous)
                return@post
            }
            var remaining = headers.size
            for (header in headers) {
                cookies.setCookie(url, header) {
                    remaining--
                    if (remaining == 0) {
                        if (!isCurrent()) result.complete(null)
                        else {
                            cookies.flush()
                            val updated = cookies.getCookie(url).orEmpty()
                            // A legacy native snapshot can outlive WebView's session cookies.
                            // Do not replace it with an incomplete jar and lose authentication.
                            val complete = names(updated).containsAll(names(previous))
                            result.complete(if (complete) updated else previous)
                        }
                    }
                }
            }
        }
        return result
    }

    private fun names(header: String): Set<String> =
        header
            .split(';')
            .mapNotNull { entry ->
                entry.substringBefore('=', "").trim().takeIf { it.isNotEmpty() }
            }
            .toSet()
}
