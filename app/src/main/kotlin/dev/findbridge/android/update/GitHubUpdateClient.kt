package dev.findbridge.android.update

import dev.findbridge.android.BoundedInput
import java.net.HttpURLConnection
import java.net.URL

/** Public release metadata only: no app account, cookie, token, or location is sent. */
internal class GitHubUpdateClient(
    private val connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }
) {
    @Volatile private var active: HttpURLConnection? = null
    @Volatile private var canceled = false

    fun cancel() {
        canceled = true
        active?.disconnect()
    }

    data class Response(val body: String?, val etag: String?)

    fun releases(etag: String?, previews: Boolean = true): Response {
        check(!canceled)
        val connection =
            connect(
                URL(
                    if (previews)
                        "https://api.github.com/repos/ryuya0124/FindBridge/releases?per_page=30"
                    else "https://api.github.com/repos/ryuya0124/FindBridge/releases/latest"
                )
            )
        active = connection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "FindBridge-Updater")
            connection.setRequestProperty("X-GitHub-Api-Version", "2026-03-10")
            etag?.let { connection.setRequestProperty("If-None-Match", it) }
            if (connection.responseCode == 304) return Response(null, etag)
            if (!previews && connection.responseCode == 404) return Response("[]", null)
            check(connection.responseCode == 200)
            val body =
                connection.inputStream.use { input ->
                    BoundedInput.utf8(input, 1_048_576, connection.contentLengthLong) { !canceled }
                }
            return Response(if (previews) body else "[$body]", connection.getHeaderField("ETag"))
        } finally {
            active = null
            connection.disconnect()
        }
    }
}
