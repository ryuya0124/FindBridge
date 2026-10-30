package dev.findbridge.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.LruCache
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.Executors

/** Bounded, in-memory original device artwork. Download and decode never block the UI. */
internal object DeviceImages {
    private val main = Handler(Looper.getMainLooper())
    private val workers = Executors.newFixedThreadPool(2)
    private val cache =
        object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
        }
    private val pending = mutableMapOf<String, MutableList<(Bitmap?) -> Unit>>()
    private val failures = mutableMapOf<String, Long>()
    private val listeners = mutableSetOf<() -> Unit>()

    fun peek(url: String): Bitmap? = cache.get(url)

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    fun load(url: String, completed: ((Bitmap?) -> Unit)? = null) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!DeviceImageUrls.allowed(url)) {
            completed?.invoke(null)
            return
        }
        cache.get(url)?.let {
            completed?.invoke(it)
            return
        }
        if (SystemClock.elapsedRealtime() - (failures[url] ?: -60_000L) < 60_000) {
            completed?.invoke(null)
            return
        }
        pending[url]?.let {
            completed?.let(it::add)
            return
        }
        val callbacks = mutableListOf<(Bitmap?) -> Unit>()
        completed?.let(callbacks::add)
        pending[url] = callbacks
        workers.execute {
            val bitmap = runCatching { download(url) }.getOrNull()
            main.post {
                if (bitmap != null) {
                    cache.put(url, bitmap)
                    failures.remove(url)
                } else {
                    if (failures.size >= 64) failures.clear()
                    failures[url] = SystemClock.elapsedRealtime()
                }
                pending.remove(url)?.forEach { it(bitmap) }
                if (bitmap != null) listeners.toList().forEach { it() }
            }
        }
    }

    private fun download(initial: String): Bitmap {
        var address = initial
        repeat(3) {
            require(DeviceImageUrls.allowed(address))
            val connection = URI(address).toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 6000
            connection.readTimeout = 8000
            connection.setRequestProperty("Accept", "image/webp,image/png,image/jpeg,image/gif")
            try {
                val status = connection.responseCode
                if (status == 404 && DeviceImageUrls.apple(address).isNotEmpty()) {
                    val next =
                        when {
                            address.endsWith("__3x.png") ->
                                address.removeSuffix("__3x.png") + "__2x.png"
                            address.endsWith("__2x.png") ->
                                address.removeSuffix("__2x.png") + ".png"
                            else -> ""
                        }
                    if (next.isNotEmpty()) {
                        address = next
                        return@repeat
                    }
                }
                if (status in setOf(301, 302, 303, 307, 308)) {
                    address = URI(address).resolve(connection.getHeaderField("Location")).toString()
                    return@repeat
                }
                check(
                    status == 200 &&
                        connection.contentType.orEmpty().substringBefore(';').startsWith("image/")
                )
                require(connection.contentLengthLong <= 524288)
                val bytes =
                    connection.inputStream.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(4096)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= 524288)
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                require(options.outWidth in 1..2048 && options.outHeight in 1..2048)
                options.inJustDecodeBounds = false
                options.inSampleSize = 1
                while (
                    maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 256
                ) options.inSampleSize *= 2
                return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options))
            } finally {
                connection.disconnect()
            }
        }
        error("Artwork redirect limit")
    }
}
