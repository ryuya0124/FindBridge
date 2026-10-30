package dev.findbridge.android

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import org.maplibre.android.maps.MapLibreMap

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
        map.snapshot { snapshot ->
            handler.post {
                if (disposed) return@post
                val targetWidth = minOf(snapshot.width, 640)
                val targetHeight =
                    (snapshot.height.toFloat() * targetWidth / snapshot.width)
                        .toInt()
                        .coerceAtLeast(1)
                worker.execute {
                    val scaled =
                        Bitmap.createScaledBitmap(snapshot, targetWidth, targetHeight, true)
                    val blurred = GlassBlur.create(scaled)
                    handler.post publish@{
                        pending = false
                        if (disposed) return@publish
                        bitmap = blurred
                        observers.removeAll { it.get() == null }
                        observers.forEach { it.get()?.invalidateSelf() }
                    }
                }
            }
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
