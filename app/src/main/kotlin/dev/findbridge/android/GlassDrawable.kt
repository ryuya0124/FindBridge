package dev.findbridge.android

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View

/** Backdrop blur, lens distortion and a light-catching edge, rendered by the Android GPU. */
internal class GlassDrawable(
    private val scene: GlassScene,
    private val host: View,
    private val radius: Float,
    private val material: Float = .45f,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val path = Path()
    private val rect = RectF()
    private val renderer = if (android.os.Build.VERSION.SDK_INT >= 33) GlassShader33() else null
    private var drawableAlpha = 255

    init {
        scene.register(this)
    }

    override fun draw(canvas: Canvas) {
        rect.set(bounds)
        val bitmap = scene.bitmap
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
            )
        } else {
            canvas.save()
            path.reset()
            path.addRoundRect(rect, radius, radius, Path.Direction.CW)
            canvas.clipPath(path)
            bitmap?.let {
                paint.color = Color.WHITE
                paint.alpha = drawableAlpha
                canvas.drawBitmap(
                    it,
                    null,
                    RectF(
                        -origin.first,
                        -origin.second,
                        scene.width - origin.first,
                        scene.height - origin.second,
                    ),
                    paint,
                )
            }
            paint.color = if (Ui.dark) 0xe82b2b31.toInt() else 0xe8fafafd.toInt()
            canvas.drawRoundRect(rect, radius, radius, paint)
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

@androidx.annotation.RequiresApi(33)
private class GlassShader33 {
    private val shader =
        RuntimeShader(
            """
            uniform shader scene;
            uniform float2 origin;
            uniform float2 panelSize;
            uniform float sceneScale;
            uniform float corner;
            uniform float material;
            uniform float dark;
            uniform float strength;
            half4 main(float2 p) {
                float2 halfSize=panelSize*0.5;
                float2 q=p-halfSize;
                float r=min(corner,min(halfSize.x,halfSize.y));
                float2 k=abs(q)-(halfSize-r);
                float distance=length(max(k,0.0))+min(max(k.x,k.y),0.0)-r;
                float alpha=1.0-smoothstep(-0.8,0.8,distance);
                if(alpha<0.001) return half4(0.0);
                float2 n=q/max(length(q),0.001);
                float edge=1.0-smoothstep(0.0,26.0,-distance);
                float2 samplePoint=(p+origin+n*edge*edge*(5.0+strength*9.0))*sceneScale;
                half4 c=scene.eval(samplePoint);
                float3 rgb=float3(c.rgb);
                float luminance=dot(rgb,float3(0.2126,0.7152,0.0722));
                rgb=mix(float3(luminance),rgb,1.12);
                float tint=mix(0.36+material*0.40,0.40+material*0.43,dark)*mix(1.20,0.70,strength);
                float3 glassColor=mix(float3(0.985,0.986,1.0),float3(0.075,0.078,0.09),dark);
                rgb=mix(rgb,glassColor,tint);
                float shine=exp(-pow((distance+1.3)/1.35,2.0));
                rgb+=shine*(0.12+0.18*max(-n.y,0.0))*(0.5+strength*0.5);
                rgb+=edge*0.025*max(-n.y,0.0);
                return half4(clamp(rgb,0.0,1.0)*alpha,alpha);
            }
            """
                .trimIndent()
        )
    private var previous: Bitmap? = null
    private val blurNode = RenderNode("FindBridgeGlass")
    private val clip = Path()
    private var lastBlur = -1f

    fun draw(
        canvas: Canvas,
        paint: Paint,
        bitmap: Bitmap,
        bounds: RectF,
        origin: Pair<Float, Float>,
        scene: GlassScene,
        radius: Float,
        material: Float,
        alpha: Int,
    ) {
        if (previous !== bitmap) {
            shader.setInputShader(
                "scene",
                BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP),
            )
            previous = bitmap
        }
        shader.setFloatUniform("origin", origin.first, origin.second)
        shader.setFloatUniform("panelSize", bounds.width(), bounds.height())
        shader.setFloatUniform("sceneScale", bitmap.width.toFloat() / scene.width.coerceAtLeast(1))
        shader.setFloatUniform("corner", radius)
        shader.setFloatUniform("material", material)
        shader.setFloatUniform("dark", if (Ui.dark) 1f else 0f)
        shader.setFloatUniform("strength", scene.intensity)
        paint.shader = shader
        paint.alpha = alpha
        paint.color = Color.WHITE
        blurNode.setPosition(0, 0, bounds.width().toInt(), bounds.height().toInt())
        val blurRadius = 10f + material * 18f
        if (lastBlur != blurRadius) {
            blurNode.setRenderEffect(
                RenderEffect.createBlurEffect(blurRadius, blurRadius, Shader.TileMode.CLAMP)
            )
            lastBlur = blurRadius
        }
        val recording = blurNode.beginRecording()
        recording.drawRect(bounds, paint)
        blurNode.endRecording()
        canvas.save()
        clip.reset()
        clip.addRoundRect(bounds, radius, radius, Path.Direction.CW)
        canvas.clipPath(clip)
        canvas.drawRenderNode(blurNode)
        canvas.restore()
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.3f
        paint.color = if (Ui.dark) 0x56ffffff else 0x9effffff.toInt()
        canvas.drawRoundRect(
            bounds.left + .7f,
            bounds.top + .7f,
            bounds.right - .7f,
            bounds.bottom - .7f,
            radius,
            radius,
            paint,
        )
        paint.style = Paint.Style.FILL
    }
}
