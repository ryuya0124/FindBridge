package dev.findbridge.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View

/** Backdrop blur, lens distortion and a light-catching edge, rendered by the Android GPU. */
internal class GlassDrawable(
    private val scene: GlassScene,
    internal val host: View,
    private val radius: Float,
    private val material: Float = .45f,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val path = Path()
    private val rect = RectF()
    private val renderer = if (android.os.Build.VERSION.SDK_INT >= 33) GlassShader33() else null
    private var drawableAlpha = 255
    var contact = 0f
        set(value) {
            field = value
            invalidateSelf()
        }

    init {
        scene.register(this)
    }

    override fun draw(canvas: Canvas) {
        rect.set(bounds)
        val current =
            if (android.os.Build.VERSION.SDK_INT >= 33)
                scene.liveBackdrop?.frame?.bitmap ?: scene.bitmap
            else scene.bitmap
        val bitmap = current?.takeIf {
            canvas.isHardwareAccelerated || it.config != Bitmap.Config.HARDWARE
        }
        val origin = scene.origin(host)
        if (
            android.os.Build.VERSION.SDK_INT >= 33 &&
                bitmap != null &&
                canvas.isHardwareAccelerated &&
                renderer != null
        ) {
            renderer.draw(
                canvas,
                paint,
                bitmap,
                rect,
                origin,
                scene,
                radius,
                material,
                drawableAlpha,
                contact,
            )
        } else {
            canvas.save()
            path.reset()
            path.addRoundRect(rect, radius, radius, Path.Direction.CW)
            canvas.clipPath(path)
            bitmap?.let {
                paint.color = Color.WHITE
                paint.alpha = drawableAlpha
                canvas.save()
                canvas.translate(-origin.first, -origin.second)
                canvas.concat(scene.viewportTransform)
                canvas.drawBitmap(
                    it,
                    null,
                    RectF(
                        0f,
                        0f,
                        scene.width.toFloat(),
                        scene.height.toFloat(),
                    ),
                    paint,
                )
                canvas.restore()
            }
            // Before the map-only backdrop arrives, keep underlying UI text out
            // of the material instead of blending the sheet through the modal.
            val tintAlpha = if (bitmap == null) 255 else 232
            paint.color = if (Ui.dark) 0xff2b2b31.toInt() else 0xfffafafd.toInt()
            paint.alpha = tintAlpha * drawableAlpha / 255
            canvas.drawRoundRect(rect, radius, radius, paint)
            if (contact > 0f) {
                paint.color = android.graphics.Color.argb((contact * 26).toInt(), 255, 255, 255)
                canvas.drawRoundRect(rect, radius, radius, paint)
            }
            canvas.restore()
        }
    }

    override fun getOutline(outline: Outline) {
        outline.setRoundRect(bounds, radius)
    }

    override fun setAlpha(alpha: Int) {
        drawableAlpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Drawable compatibility") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
