package dev.findbridge.android

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView

/** Supplies real map pixels to glass backgrounds. UI text is never part of the blurred scene. */
internal class GlassScene(private val mapView: View) {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val observers = mutableListOf<WeakReference<GlassDrawable>>()
    private var pending = false
    private var lastSnapshot = 0L
    private var disposed = false
    var bitmap: Bitmap? = null
        private set

    val width
        get() = mapView.width

    val height
        get() = mapView.height

    var intensity = .65f
    private val location = IntArray(2)

    fun origin(view: View): Pair<Float, Float> {
        mapView.getLocationOnScreen(location)
        val x = location[0]
        val y = location[1]
        view.getLocationOnScreen(location)
        return (location[0] - x).toFloat() to (location[1] - y).toFloat()
    }

    fun register(drawable: GlassDrawable) {
        observers += WeakReference(drawable)
    }

    fun capture(map: MapLibreMap, immediate: Boolean = false) {
        if (disposed || pending || width == 0 || height == 0) return
        // Snapshot completion can itself trigger MapLibre's idle callback. Always bound the
        // capture rate so a still map cannot create a snapshot/blur/render feedback loop.
        if (SystemClock.uptimeMillis() - lastSnapshot < if (immediate) 100 else 140) return
        pending = true
        lastSnapshot = SystemClock.uptimeMillis()
        copyBackdrop(map) { scaled ->
            if (disposed) {
                scaled.recycle()
                return@copyBackdrop
            }
            worker.execute {
                val blurred = GlassBlur.create(scaled)
                scaled.recycle()
                handler.post publish@{
                    pending = false
                    if (disposed) {
                        blurred.recycle()
                        return@publish
                    }
                    bitmap = blurred
                    observers.removeAll { it.get() == null }
                    observers.forEach { it.get()?.invalidateSelf() }
                }
            }
        }
    }

    /**
     * Downsample at the renderer surface, before pixels reach the CPU, rather than copying a full
     * phone/tablet framebuffer and allocating another scaled image on every update.
     */
    private fun copyBackdrop(map: MapLibreMap, consume: (Bitmap) -> Unit) {
        val targetWidth = minOf(width, 640)
        val targetHeight = (height.toLong() * targetWidth / width).toInt().coerceAtLeast(1)
        fun fallback() {
            if (disposed) {
                pending = false
                return
            }
            map.snapshot { snapshot ->
                handler.post {
                    if (disposed) {
                        pending = false
                        return@post
                    }
                    val scaled =
                        Bitmap.createScaledBitmap(snapshot, targetWidth, targetHeight, true)
                    if (scaled !== snapshot) snapshot.recycle()
                    consume(scaled)
                }
            }
        }
        val renderView = (mapView as? MapView)?.renderView
        when (renderView) {
            is SurfaceView -> {
                val target = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                val request = runCatching {
                    PixelCopy.request(
                        renderView,
                        target,
                        { result ->
                            if (result == PixelCopy.SUCCESS) consume(target)
                            else {
                                target.recycle()
                                fallback()
                            }
                        },
                        handler,
                    )
                }
                if (request.isFailure) {
                    target.recycle()
                    fallback()
                }
            }
            is TextureView -> {
                val target = runCatching {
                    renderView.getBitmap(targetWidth, targetHeight)
                }
                    .getOrNull()
                if (target != null) consume(target) else fallback()
            }
            else -> fallback()
        }
    }

    fun invalidate() {
        observers.forEach { it.get()?.invalidateSelf() }
    }

    fun dispose() {
        disposed = true
        worker.shutdown()
        observers.clear()
        bitmap = null
    }
}
