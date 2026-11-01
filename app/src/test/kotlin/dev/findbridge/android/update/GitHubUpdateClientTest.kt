package dev.findbridge.android.update

import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.*
import org.junit.Test

class GitHubUpdateClientTest {
    @Test
    fun conditionalMetadataRequestIsPublicAndAlwaysDisconnects() {
        var closed = false
        val connection =
            object : HttpURLConnection(URL("https://api.github.com")) {
                override fun connect() {}

                override fun disconnect() {
                    closed = true
                }

                override fun usingProxy() = false

                override fun getResponseCode() = 304
            }
        val result = GitHubUpdateClient { url ->
            assertEquals("api.github.com", url.host)
            assertEquals("/repos/ryuya0124/FindBridge/releases", url.path)
            connection
        }
            .releases("fixture-etag")
        assertNull(result.body)
        assertEquals("fixture-etag", connection.getRequestProperty("If-None-Match"))
        assertNull(connection.getRequestProperty("Authorization"))
        assertNull(connection.getRequestProperty("Cookie"))
        assertTrue(closed)
    }

    @Test
    fun redirectAndOversizedCatalogAreRejected() {
        for (status in listOf(302, 200)) {
            var closed = false
            val connection =
                object : HttpURLConnection(URL("https://api.github.com")) {
                    override fun connect() {}

                    override fun disconnect() {
                        closed = true
                    }

                    override fun usingProxy() = false

                    override fun getResponseCode() = status

                    override fun getInputStream() = ByteArrayInputStream(ByteArray(1_048_577))
                }
            assertThrows(IllegalStateException::class.java) {
                GitHubUpdateClient { connection }.releases(null)
            }
            assertFalse(connection.instanceFollowRedirects)
            assertTrue(closed)
        }
    }

    @Test
    fun stableChannelUsesTheStableEndpointAndHandlesNoStableRelease() {
        val connection =
            object : HttpURLConnection(URL("https://api.github.com")) {
                override fun connect() {}

                override fun disconnect() {}

                override fun usingProxy() = false

                override fun getResponseCode() = 404
            }
        val response = GitHubUpdateClient { url ->
            assertEquals("/repos/ryuya0124/FindBridge/releases/latest", url.path)
            connection
        }
            .releases(null, previews = false)
        assertEquals("[]", response.body)
    }

    @Test
    fun anInFlightMetadataRequestCanBeCanceled() {
        val started = java.util.concurrent.CountDownLatch(1)
        val canceled = java.util.concurrent.CountDownLatch(1)
        val finished = java.util.concurrent.CountDownLatch(1)
        val connection =
            object : HttpURLConnection(URL("https://api.github.com")) {
                override fun connect() {}

                override fun disconnect() {
                    canceled.countDown()
                }

                override fun usingProxy() = false

                override fun getResponseCode(): Int {
                    started.countDown()
                    check(canceled.await(3, java.util.concurrent.TimeUnit.SECONDS))
                    return 503
                }
            }
        val client = GitHubUpdateClient { connection }
        val thread = Thread {
            try {
                runCatching { client.releases(null) }
            } finally {
                finished.countDown()
            }
        }
        thread.start()
        assertTrue(started.await(3, java.util.concurrent.TimeUnit.SECONDS))
        client.cancel()
        assertTrue(finished.await(3, java.util.concurrent.TimeUnit.SECONDS))
        thread.join()
    }
}
