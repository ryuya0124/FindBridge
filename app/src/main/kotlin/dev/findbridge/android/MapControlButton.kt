package dev.findbridge.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.widget.Button

/** Map, globe and location symbols drawn as vectors; no font glyph or bundled Apple artwork. */
@android.annotation.SuppressLint(
    "ViewConstructor"
) // Constructed in Kotlin with the owning map scene.
internal class MapControlButton(context: Context, scene: GlassScene, var symbol: Symbol) :
    View(context) {
    enum class Symbol {
        MAP,
        SATELLITE,
        LOCATION,
        CLOSE,
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    var active = false
        set(value) {
            field = value
            invalidate()
        }

    init {
        background = GlassDrawable(scene, this, 24 * resources.displayMetrics.density, .16f)
        elevation = 3 * resources.displayMetrics.density
        isClickable = true
        isFocusable = true
        defaultFocusHighlightEnabled = false
        NativeMotion.press(this, .91f)
    }

    override fun getAccessibilityClassName(): CharSequence = Button::class.java.name

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val size = 23 * density
        canvas.save()
        canvas.translate((width - size) / 2, (height - size) / 2)
        canvas.scale(size / 24, size / 24)
        paint.color = if (active) Ui.blue else Ui.ink
        paint.style = Paint.Style.FILL
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = Paint.Cap.ROUND
        path.reset()
        when (symbol) {
            Symbol.MAP -> {
                path.moveTo(2f, 5f)
                path.lineTo(8f, 2.5f)
                path.lineTo(16f, 5f)
                path.lineTo(22f, 2.5f)
                path.lineTo(22f, 19f)
                path.lineTo(16f, 21.5f)
                path.lineTo(8f, 19f)
                path.lineTo(2f, 21.5f)
                path.close()
                canvas.drawPath(path, paint)
                paint.color = if (Ui.dark) 0xff202026.toInt() else 0xfff4f4f6.toInt()
                paint.strokeWidth = 1f
                canvas.drawLine(8f, 3.5f, 8f, 18f, paint)
                canvas.drawLine(16f, 6f, 16f, 20.5f, paint)
            }
            Symbol.SATELLITE -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.6f
                canvas.drawCircle(12f, 12f, 9.2f, paint)
                paint.style = Paint.Style.FILL
                path.moveTo(4f, 8f)
                path.lineTo(8f, 5f)
                path.lineTo(12f, 5f)
                path.lineTo(14f, 8f)
                path.lineTo(11f, 10f)
                path.lineTo(12f, 13f)
                path.lineTo(9f, 14f)
                path.lineTo(8f, 11f)
                path.lineTo(5f, 11f)
                path.close()
                path.moveTo(15f, 11f)
                path.lineTo(20f, 10f)
                path.lineTo(20f, 15f)
                path.lineTo(17f, 18.5f)
                path.lineTo(15f, 16f)
                path.lineTo(13.5f, 14f)
                path.close()
                canvas.drawPath(path, paint)
            }
            Symbol.LOCATION -> {
                path.moveTo(3.6f, 10.5f)
                path.lineTo(21f, 3f)
                path.lineTo(13.5f, 20.4f)
                path.lineTo(11.3f, 12.7f)
                path.close()
                if (active) canvas.drawPath(path, paint)
                else {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 1.85f
                    canvas.drawPath(path, paint)
                }
            }
            Symbol.CLOSE -> {
                paint.strokeWidth = 2f
                canvas.drawLine(7f, 7f, 17f, 17f, paint)
                canvas.drawLine(17f, 7f, 7f, 17f, paint)
            }
        }
        canvas.restore()
    }
}
