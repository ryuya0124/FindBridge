package dev.findbridge.android

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class BlurSceneTest {
    @Test
    fun changingBlurReprocessesTheExistingMapPixelsWithoutAnotherCapture() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val frames = LinkedBlockingQueue<Bitmap>()
        lateinit var scene: GlassScene
        lateinit var material: GlassDrawable
        instrumentation.runOnMainSync {
            val source = Bitmap.createBitmap(640, 64, Bitmap.Config.ARGB_8888)
            for (y in 0 until source.height) for (x in 0 until source.width) source.setPixel(
                x,
                y,
                if (x % 4 < 2) Color.BLACK else Color.WHITE,
            )
            val host = View(context)
            scene = GlassScene(host)
            // A synthetic map-only source keeps this test independent of network tiles,
            // a camera and the owner's account, while exercising the real blur worker.
            GlassScene::class
                .java
                .getDeclaredField("rawBackdrop")
                .apply { isAccessible = true }
                .set(scene, source)
            material = GlassDrawable(scene, host, 12f)
            material.callback =
                object : Drawable.Callback {
                    override fun invalidateDrawable(who: Drawable) {
                        scene.bitmap?.let { frames.offer(it) }
                    }

                    override fun scheduleDrawable(
                        who: Drawable,
                        what: Runnable,
                        whenMillis: Long,
                    ) {}

                    override fun unscheduleDrawable(who: Drawable, what: Runnable) {}
                }
            scene.intensity = 0f
        }
        try {
            val sharp = frames.poll(5, TimeUnit.SECONDS)
            assertNotNull("Zero blur must publish the cached map pixels", sharp)
            assertEquals(Color.BLACK, sharp!!.getPixel(320, 32))
            instrumentation.runOnMainSync { scene.intensity = 1f }
            val blurred = frames.poll(5, TimeUnit.SECONDS)
            assertNotNull("Changing the slider must reblur without a new map capture", blurred)
            assertTrue(Color.red(blurred!!.getPixel(320, 32)) in 30..225)
        } finally {
            instrumentation.runOnMainSync {
                material.callback = null
                scene.dispose()
            }
        }
    }
}
