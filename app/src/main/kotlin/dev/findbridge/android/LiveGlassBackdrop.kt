package dev.findbridge.android

import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.view.TextureView
import androidx.annotation.RequiresApi
import kotlin.math.sqrt

/** Shares the map's current GPU texture with glass, without readback or a CPU blur queue. */
@RequiresApi(33)
internal class LiveGlassBackdrop(private val texture: TextureView) {
    val node = RenderNode("Find Bridge map glass")
    private var output: GpuGlassBuffer? = null
    var frame: GpuGlassBuffer.Frame? = null
        private set

    var width = 0
        private set

    var height = 0
        private set

    var frames = 0L
        private set

    val ready: Boolean
        get() = frame != null

    fun update(): Boolean {
        if (!texture.isAvailable || texture.width == 0 || texture.height == 0) return false
        width = texture.width
        height = texture.height
        // Keep the original 640px glass quality at every cadence, including while moving.
        val targetWidth = minOf(texture.width, 640)
        val targetHeight =
            (texture.height.toLong() * targetWidth / texture.width).toInt().coerceAtLeast(1)
        if (node.width != targetWidth || node.height != targetHeight) {
            output?.dispose()
            output = GpuGlassBuffer(targetWidth, targetHeight)
            node.setPosition(0, 0, targetWidth, targetHeight)
            // Match the spread of the previous three box passes at radius 6 / 640px.
            val radius = (6f * targetWidth / 640f).toInt().coerceAtLeast(1).toFloat()
            val sigma = sqrt(radius * (radius + 1f))
            node.setRenderEffect(RenderEffect.createBlurEffect(sigma, sigma, Shader.TileMode.CLAMP))
        }
        val canvas = node.beginRecording()
        try {
            // TextureView records its existing hardware layer; it does not copy pixels.
            canvas.scale(
                targetWidth.toFloat() / texture.width,
                targetHeight.toFloat() / texture.height,
            )
            texture.draw(canvas)
        } finally {
            node.endRecording()
        }
        val next = output?.render(node) ?: return false
        frame = next
        frames++
        return true
    }

    fun dispose() {
        frame?.close()
        frame = null
        output?.dispose()
        node.discardDisplayList()
    }
}
