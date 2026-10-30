package dev.findbridge.android

import android.graphics.Bitmap

/** Three separable box passes approximate a Gaussian blur, including on pre-AGSL devices. */
internal object GlassBlur {
    fun create(source: Bitmap, radius: Int = 6): Bitmap {
        val width = source.width
        val height = source.height
        var pixels = IntArray(width * height)
        var scratch = IntArray(pixels.size)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val diameter = radius * 2 + 1
        fun pass(horizontal: Boolean) {
            val lines = if (horizontal) height else width
            val length = if (horizontal) width else height
            val stride = if (horizontal) 1 else width
            for (line in 0 until lines) {
                val start = if (horizontal) line * width else line
                var red = 0
                var green = 0
                var blue = 0
                fun add(position: Int, multiplier: Int) {
                    val color = pixels[start + position.coerceIn(0, length - 1) * stride]
                    red += (color ushr 16 and 255) * multiplier
                    green += (color ushr 8 and 255) * multiplier
                    blue += (color and 255) * multiplier
                }
                for (offset in -radius..radius) add(offset, 1)
                for (position in 0 until length) {
                    scratch[start + position * stride] =
                        0xff000000.toInt() or
                            ((red / diameter) shl 16) or
                            ((green / diameter) shl 8) or
                            (blue / diameter)
                    add(position - radius, -1)
                    add(position + radius + 1, 1)
                }
            }
            val swap = pixels
            pixels = scratch
            scratch = swap
        }
        repeat(3) {
            pass(true)
            pass(false)
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}
