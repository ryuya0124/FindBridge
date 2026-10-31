package dev.findbridge.android

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.Image
import android.media.ImageReader
import androidx.annotation.RequiresApi

/** One shared GPU blur target; glass controls sample its hardware buffer directly. */
@RequiresApi(33)
internal class GpuGlassBuffer(width: Int, height: Int) {
    private val reader =
        ImageReader.newInstance(
            width,
            height,
            PixelFormat.RGBA_8888,
            6,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT,
        )
    private val renderer =
        HardwareRenderer().apply {
            setSurface(reader.surface)
            setOpaque(true)
        }
    private var acquired = 0

    fun render(node: RenderNode): Frame? {
        if (acquired >= 5) return null
        renderer.setContentRoot(node)
        // Synchronize the actual GPU work once. No pixels are read or blurred by the CPU.
        renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
        val image = reader.acquireLatestImage() ?: return null
        val buffer = image.hardwareBuffer
        val bitmap = buffer?.let {
            Bitmap.wrapHardwareBuffer(it, ColorSpace.get(ColorSpace.Named.SRGB))
        }
        buffer?.close()
        if (bitmap == null) {
            image.close()
            return null
        }
        acquired++
        return Frame(image, bitmap) { acquired-- }
    }

    fun dispose() {
        renderer.destroy()
        reader.close()
    }

    class Frame(private val image: Image, val bitmap: Bitmap, private val released: () -> Unit) {
        private var closed = false

        fun close() {
            if (closed) return
            closed = true
            image.close()
            bitmap.recycle()
            released()
        }
    }
}
