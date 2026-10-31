package dev.findbridge.android

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewTreeObserver
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
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
    private var active = true
    private var cameraSample: CameraSample? = null
    private var movingMap: MapLibreMap? = null
    private var lastCapturedCamera: CameraPosition? = null
    private val live =
        if (android.os.Build.VERSION.SDK_INT >= 33)
            ((mapView as? MapView)?.renderView as? TextureView)?.let(::LiveGlassBackdrop)
        else null
    val liveBackdrop: LiveGlassBackdrop?
        get() = if (android.os.Build.VERSION.SDK_INT >= 33) live?.takeIf { it.ready } else null

    private var liveDirty = true
    private val retired = mutableSetOf<GpuGlassBuffer.Frame>()
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        if (
            android.os.Build.VERSION.SDK_INT >= 33 &&
                !disposed &&
                active &&
                live != null &&
                (liveDirty || movingMap != null || live.width != width || live.height != height)
        ) {
            val previous = live.frame
            if (live.update()) {
                liveDirty = false
                invalidate()
                previous?.let(::retire)
            }
        }
        true
    }

    init {
        mapView.viewTreeObserver.addOnPreDrawListener(preDraw)
    }

    private val motionFrame =
        object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                val current = movingMap ?: return
                if (disposed || !mapView.isAttachedToWindow) {
                    stopTracking()
                    return
                }
                trackCamera(current)
                capture(current)
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    val viewportTransform = Matrix()
    private val inverseTransform = Matrix()
    val samplingTransform =
        FloatArray(9).apply {
            this[0] = 1f
            this[4] = 1f
            this[8] = 1f
        }
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

    /** Keep a sampled buffer until every visible consumer window commits its replacement. */
    @androidx.annotation.RequiresApi(33)
    private fun retire(frame: GpuGlassBuffer.Frame) {
        val roots =
            observers
                .mapNotNull { it.get()?.host }
                .filter { it.isAttachedToWindow && it.isShown }
                .map { it.rootView }
                .distinct()
        if (roots.isEmpty()) {
            frame.close()
            return
        }
        retired += frame
        var remaining = roots.size
        roots.forEach { root ->
            var finished = false
            lateinit var detached: View.OnAttachStateChangeListener
            fun acknowledge() {
                handler.post {
                    if (!finished) {
                        finished = true
                        root.removeOnAttachStateChangeListener(detached)
                        remaining--
                        if (remaining == 0) {
                            retired.remove(frame)
                            frame.close()
                            if (liveDirty && !disposed) onMapFrame()
                        }
                    }
                }
            }
            detached =
                object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(view: View) = Unit

                    override fun onViewDetachedFromWindow(view: View) = acknowledge()
                }
            root.addOnAttachStateChangeListener(detached)
            root.viewTreeObserver.registerFrameCommitCallback { acknowledge() }
            root.postInvalidateOnAnimation()
        }
    }

    fun capture(map: MapLibreMap, immediate: Boolean = false) {
        if (live != null) {
            onMapFrame()
            return
        }
        if (disposed || !active || pending || width == 0 || height == 0) return
        val moving = movingMap != null
        val position = map.cameraPosition
        if (moving) {
            // Motion is driven by Vsync, not a 140ms timer. Coalesce a stationary camera
            // and keep only one readback/blur in flight; never accumulate old frames.
            if (lastCapturedCamera == position) return
        } else {
            // A final high-resolution copy refines the moving image immediately. Other
            // idle callbacks stay bounded to avoid snapshot/render feedback.
            val refining = bitmap?.width?.let { it < minOf(width, 640) } == true
            if (
                !refining && SystemClock.uptimeMillis() - lastSnapshot < if (immediate) 100 else 140
            )
                return
        }
        pending = true
        lastSnapshot = SystemClock.uptimeMillis()
        lastCapturedCamera = position
        val sample = CameraSample.capture(map, width, height)
        copyBackdrop(map) { scaled ->
            if (disposed) {
                scaled.recycle()
                return@copyBackdrop
            }
            worker.execute {
                val radius = (6f * scaled.width / 640f).toInt().coerceAtLeast(1)
                val blurred = GlassBlur.create(scaled, radius)
                scaled.recycle()
                handler.post publish@{
                    pending = false
                    if (disposed) {
                        blurred.recycle()
                        return@publish
                    }
                    bitmap = blurred
                    cameraSample = sample
                    trackCamera(map)
                    if (movingMap == null && blurred.width < minOf(width, 640))
                        capture(map, immediate = true)
                }
            }
        }
    }

    /** Reprojects the latest pixels on every display frame, independently of GPU readback. */
    fun trackCamera(map: MapLibreMap) {
        if (live != null) {
            onMapFrame()
            return
        }
        val sample = cameraSample
        viewportTransform.reset()
        if (sample != null && sample.width == width && sample.height == height) {
            val current = FloatArray(8)
            sample.ground.forEachIndexed { index, coordinate ->
                val point = map.projection.toScreenLocation(coordinate)
                current[index * 2] = point.x
                current[index * 2 + 1] = point.y
            }
            if (current.all { it.isFinite() })
                viewportTransform.setPolyToPoly(sample.screen, 0, current, 0, 4)
        }
        if (!viewportTransform.invert(inverseTransform)) inverseTransform.reset()
        inverseTransform.getValues(samplingTransform)
        invalidate()
    }

    fun startTracking(map: MapLibreMap) {
        if (disposed || !active || movingMap != null) return
        movingMap = map
        lastCapturedCamera = null
        if (live == null) Choreographer.getInstance().postFrameCallback(motionFrame)
        else onMapFrame()
    }

    /** Camera and renderer events request the next actual draw, without a sampling timer. */
    fun onMapFrame() {
        if (disposed || !active || live == null) return
        liveDirty = true
        mapView.postInvalidateOnAnimation()
    }

    fun stopTracking() {
        movingMap = null
        Choreographer.getInstance().removeFrameCallback(motionFrame)
    }

    fun setActive(value: Boolean) {
        active = value
        if (!value) stopTracking() else onMapFrame()
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
        observers.removeAll { it.get() == null }
        observers.forEach { it.get()?.invalidateSelf() }
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        stopTracking()
        if (mapView.viewTreeObserver.isAlive)
            mapView.viewTreeObserver.removeOnPreDrawListener(preDraw)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            live?.dispose()
            retired.forEach { it.close() }
        }
        retired.clear()
        worker.shutdown()
        observers.clear()
        bitmap = null
        cameraSample = null
    }

    private class CameraSample(
        val width: Int,
        val height: Int,
        val screen: FloatArray,
        val ground: List<LatLng>,
    ) {
        companion object {
            fun capture(map: MapLibreMap, width: Int, height: Int): CameraSample {
                // Interior points avoid a tilted viewport's horizon and preserve the
                // planar map's projective pan/zoom/bearing/pitch transform.
                val screen =
                    floatArrayOf(
                        width * .25f,
                        height * .25f,
                        width * .75f,
                        height * .25f,
                        width * .75f,
                        height * .75f,
                        width * .25f,
                        height * .75f,
                    )
                val ground =
                    (0..3).map { index ->
                        map.projection.fromScreenLocation(
                            PointF(screen[index * 2], screen[index * 2 + 1])
                        )
                    }
                return CameraSample(width, height, screen, ground)
            }
        }
    }
}
