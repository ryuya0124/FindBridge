package dev.findbridge.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.View
import java.util.Locale

/** Original vector silhouettes, without bundled Apple artwork or SF Symbols. */
internal class DeviceGlyph
@JvmOverloads
constructor(context: Context, private val kind: String = "iPhone") : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = minOf(width, height).toFloat()
        canvas.save()
        canvas.translate((width - size) / 2, (height - size) / 2)
        canvas.scale(size / 64, size / 64)
        drawDevice(canvas, paint, kind)
        canvas.restore()
    }

    companion object {
        fun marker(context: Context, kind: String, selected: Boolean): Bitmap {
            val density = context.resources.displayMetrics.density
            val size = ((if (selected) 66 else 52) * density).toInt()
            val bitmap =
                Bitmap.createBitmap(size, size + (8 * density).toInt(), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            canvas.scale(size / 64f, size / 64f)
            paint.color = 0x27000000
            canvas.drawCircle(32f, 33f, 30f, paint)
            paint.color = Color.WHITE
            canvas.drawCircle(32f, 31f, 28f, paint)
            canvas.drawPath(
                Path().apply {
                    moveTo(25f, 53f)
                    lineTo(32f, 66f)
                    lineTo(39f, 53f)
                    close()
                },
                paint,
            )
            if (selected) {
                paint.color = Ui.green
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2f
                canvas.drawCircle(32f, 31f, 28f, paint)
                paint.style = Paint.Style.FILL
            }
            canvas.save()
            canvas.translate(10f, 8f)
            canvas.scale(.7f, .7f)
            drawDevice(canvas, paint, kind)
            canvas.restore()
            return bitmap
        }

        private fun round(
            c: Canvas,
            p: Paint,
            color: Int,
            l: Float,
            t: Float,
            r: Float,
            b: Float,
            radius: Float,
        ) {
            p.shader = null
            p.color = color
            c.drawRoundRect(l, t, r, b, radius, radius, p)
        }

        private fun drawDevice(c: Canvas, p: Paint, kind: String) {
            val type = kind.lowercase(Locale.ROOT)
            when {
                "watch" in type -> {
                    round(c, p, 0xffc8c6bc.toInt(), 24f, 0f, 42f, 64f, 8f)
                    round(c, p, 0xffa9a69e.toInt(), 15f, 14f, 50f, 51f, 11f)
                    round(c, p, 0xff151719.toInt(), 18f, 17f, 47f, 48f, 9f)
                    p.color = 0xffa9a69e.toInt()
                    c.drawCircle(51f, 27f, 3f, p)
                    p.color = 0xfff59d42.toInt()
                    p.strokeWidth = 2.5f
                    c.drawLine(32f, 32f, 32f, 23f, p)
                    c.drawLine(32f, 32f, 40f, 36f, p)
                }
                "mac" in type || "desktop" in type -> {
                    round(c, p, 0xffbbbbc0.toInt(), 4f, 10f, 60f, 48f, 4f)
                    round(c, p, 0xff24252a.toInt(), 6f, 12f, 58f, 44f, 2f)
                    p.shader =
                        LinearGradient(
                            6f,
                            12f,
                            58f,
                            44f,
                            0xff626a82.toInt(),
                            0xffb6c8c2.toInt(),
                            Shader.TileMode.CLAMP,
                        )
                    c.drawRect(9f, 15f, 55f, 41f, p)
                    p.shader = null
                    round(c, p, 0xffbebec3.toInt(), 28f, 48f, 36f, 56f, 1f)
                    round(c, p, 0xffa9a9af.toInt(), 19f, 55f, 45f, 58f, 2f)
                }
                "accessory" in type || "airpod" in type -> {
                    round(c, p, 0xffececef.toInt(), 8f, 25f, 56f, 58f, 13f)
                    round(c, p, Color.WHITE, 9f, 24f, 55f, 43f, 12f)
                    round(c, p, 0xffd7d7dc.toInt(), 30f, 38f, 34f, 40f, 1f)
                    round(c, p, 0xffe1e1e6.toInt(), 18f, 5f, 25f, 27f, 3f)
                    round(c, p, 0xffe1e1e6.toInt(), 39f, 5f, 46f, 27f, 3f)
                }
                else -> {
                    val tablet = "ipad" in type
                    val left = if (tablet) 9f else 17f
                    val right = if (tablet) 55f else 47f
                    round(c, p, 0xffa7a5a2.toInt(), left, 1f, right, 63f, if (tablet) 6f else 8f)
                    round(c, p, 0xff1e2025.toInt(), left + 1.5f, 2.5f, right - 1.5f, 61.5f, 6f)
                    p.shader =
                        LinearGradient(
                            left,
                            4f,
                            right,
                            60f,
                            intArrayOf(
                                0xffc8baab.toInt(),
                                0xffe1d9c8.toInt(),
                                0xff5b566f.toInt(),
                                0xff2a354d.toInt(),
                            ),
                            null,
                            Shader.TileMode.CLAMP,
                        )
                    c.drawRoundRect(left + 3, 5f, right - 3, 59f, 4f, 4f, p)
                    p.shader = null
                    round(c, p, 0xff111116.toInt(), 25f, 5f, 39f, 9f, 2f)
                }
            }
        }
    }
}
