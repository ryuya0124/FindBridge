package dev.findbridge.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** Original outline icons; no platform-specific licensed font is included. */
internal class TabGlyph @JvmOverloads constructor(context: Context, private val kind: Int = 1) :
    View(context) {
    var tint: Int = Ui.muted
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val peoplePath =
        Path().apply {
            moveTo(20f, 16f)
            cubicTo(25f, 16f, 26f, 20f, 25f, 24f)
        }
    private val tagPath =
        Path().apply {
            moveTo(4f, 4f)
            lineTo(15f, 4f)
            lineTo(25f, 14f)
            quadTo(26f, 15f, 25f, 17f)
            lineTo(17f, 25f)
            quadTo(15f, 26f, 14f, 25f)
            lineTo(4f, 15f)
            close()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        val size = minOf(width, height).toFloat()
        canvas.translate((width - size) / 2, (height - size) / 2)
        canvas.scale(size / 28, size / 28)
        paint.color = tint
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.9f
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        when (kind) {
            0 -> {
                canvas.drawCircle(10f, 8f, 3.5f, paint)
                canvas.drawCircle(20f, 10f, 2.7f, paint)
                canvas.drawRoundRect(3f, 15f, 17f, 24f, 5f, 5f, paint)
                canvas.drawPath(
                    peoplePath,
                    paint,
                )
            }
            1 -> {
                canvas.drawRoundRect(2f, 4f, 23f, 19f, 2f, 2f, paint)
                canvas.drawLine(10f, 19f, 10f, 24f, paint)
                canvas.drawLine(6f, 24f, 15f, 24f, paint)
                paint.style = Paint.Style.FILL
                paint.color = Ui.surface
                canvas.drawRoundRect(18f, 11f, 27f, 27f, 2f, 2f, paint)
                paint.style = Paint.Style.STROKE
                paint.color = tint
                canvas.drawRoundRect(18f, 11f, 27f, 27f, 2f, 2f, paint)
            }
            2 -> {
                canvas.drawPath(
                    tagPath,
                    paint,
                )
                canvas.drawCircle(9f, 9f, 1.8f, paint)
            }
            else -> {
                canvas.drawCircle(14f, 14f, 11.5f, paint)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(14f, 10f, 4f, paint)
                canvas.drawRoundRect(6.5f, 16f, 21.5f, 23f, 7f, 7f, paint)
            }
        }
        canvas.restore()
    }
}
