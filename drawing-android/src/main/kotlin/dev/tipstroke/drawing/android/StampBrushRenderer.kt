package dev.tipstroke.drawing.android

import android.graphics.*
import dev.tipstroke.core.drawing.*
import dev.tipstroke.core.geometry.Rect
import dev.tipstroke.core.model.*
import kotlin.math.*

/** CPU raster stamps. Sources are prepared once per stroke, never loaded from disk on input. */
internal class StampBrushRenderer(private val style: StrokeStyle) : AutoCloseable {
    private val config = requireNotNull(style.brush.stamp)
    val path = StampPath(style)
    private val shape = shapeBitmap(config, style.brush.hardness)
    private val hasGrain = config.grain != StampGrain.NONE && config.grainDepth > 0f
    private val grain = if (hasGrain) grainBitmap(config) else null
    private val shapeShader = BitmapShader(shape, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    private val grainShader = grain?.let { BitmapShader(it, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        shader = grainShader?.let { ComposeShader(shapeShader, it, PorterDuff.Mode.DST_IN) }
    }
    private val hsv = FloatArray(3).also { Color.colorToHSV(Color.rgb((style.color.red * 255).roundToInt(), (style.color.green * 255).roundToInt(), (style.color.blue * 255).roundToInt()), it) }
    private val tint = FloatArray(3)
    private val matrix = Matrix()
    private var lastTint: Int? = null
    private val fixedTipEligible = !hasGrain && config.rotationJitter == 0f && !config.followDirection &&
        config.sizeJitter == 0f && config.tiltSize == 0f && config.startTaperPx == 0f &&
        config.hueJitter == 0f && config.saturationJitter == 0f && style.brush.speedTaper == 0f &&
        style.brush.pressureToSize.start == style.brush.pressureToSize.end
    private var fixedTip: Bitmap? = null
    private var fixedTipScale = 1f
    private val blitPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val destination = RectF()

    fun draw(canvas: Canvas, stamps: List<BrushStamp>) {
        val saved = canvas.save()
        style.selection?.let { canvas.clipPath(it.toAndroidPath()) }
        stamps.forEach { stamp ->
            if (stamp.alpha <= 0f) return@forEach
            val bounds = bounds(stamp)
            if (canvas.quickReject(bounds.left, bounds.top, bounds.right, bounds.bottom, Canvas.EdgeType.AA)) return@forEach
            matrix.reset()
            matrix.postTranslate(-shape.width / 2f, -shape.height / 2f)
            matrix.postScale(stamp.size / shape.width, stamp.size * config.aspectRatio / shape.height)
            matrix.postRotate(stamp.angle)
            matrix.postTranslate(stamp.x, stamp.y)
            if (grainShader != null && grain != null) {
                shapeShader.setLocalMatrix(matrix)
            }
            tint[0] = (hsv[0] + stamp.hue + 360f) % 360f
            tint[1] = (hsv[1] + stamp.saturation).coerceIn(0f, 1f)
            tint[2] = hsv[2]
            val color = Color.HSVToColor(tint)
            if (color != lastTint) {
                paint.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
                lastTint = color
            }
            paint.alpha = (stamp.alpha * 255).roundToInt()
            if (grainShader == null || grain == null) {
                // A bitmap blit avoids evaluating two shaders over a mostly transparent
                // bounding square for every overlapping stamp. Flow and spacing stay exact.
                if (fixedTipEligible) drawFixedTip(canvas, stamp)
                else canvas.drawBitmap(shape, matrix, paint)
            } else {
                matrix.reset()
                matrix.setScale(config.grainScale / grain.width, config.grainScale / grain.height)
                if (config.grainPlacement == GrainPlacement.STAMP) matrix.postTranslate(stamp.x - stamp.size / 2f, stamp.y - stamp.size / 2f)
                grainShader.setLocalMatrix(matrix)
                canvas.drawRect(bounds.left, bounds.top, bounds.right, bounds.bottom, paint)
            }
        }
        canvas.restoreToCount(saved)
    }

    private fun drawFixedTip(canvas: Canvas, stamp: BrushStamp) {
        val tip = fixedTip ?: run {
            // Bake a stable size/angle/tint once. Dense round brushes otherwise repeat
            // the same resampling and color filter dozens of times at each position.
            // Only a tip is cached, never artwork; cap it independently of canvas size.
            fixedTipScale = min(1f, 1020f / (stamp.size * 1.44f))
            val edge = (ceil(stamp.size * 1.44f * fixedTipScale).toInt() + 4).coerceAtMost(1024)
            Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888).also { bitmap ->
                val scratch = Canvas(bitmap)
                scratch.translate(edge / 2f, edge / 2f)
                scratch.scale(fixedTipScale, fixedTipScale)
                scratch.rotate(stamp.angle)
                scratch.scale(stamp.size / shape.width, stamp.size * config.aspectRatio / shape.height)
                val alpha = paint.alpha
                paint.alpha = 255
                scratch.drawBitmap(shape, -shape.width / 2f, -shape.height / 2f, paint)
                paint.alpha = alpha
                fixedTip = bitmap
            }
        }
        val half = tip.width / (2f * fixedTipScale)
        destination.set(stamp.x - half, stamp.y - half, stamp.x + half, stamp.y + half)
        blitPaint.alpha = paint.alpha
        canvas.drawBitmap(tip, null, destination, blitPaint)
    }

    fun drawClipped(canvas: Canvas, stamps: List<BrushStamp>) {
        val reference = style.referenceStrokes as? ReferenceStrokes
        if (reference == null) { draw(canvas, stamps); return }
        val clip = canvas.clipBounds
        val saved = canvas.saveLayer(RectF(clip), Paint().apply {
            xfermode = PorterDuffXfermode(if (reference.sameLayer) PorterDuff.Mode.SRC_ATOP else PorterDuff.Mode.SRC_OVER)
        })
        draw(canvas, stamps)
        reference.drawCoverage(canvas, clip)
        canvas.restoreToCount(saved)
    }

    override fun close() { shape.recycle(); grain?.recycle(); fixedTip?.recycle(); fixedTip = null }

    companion object {
        fun bounds(stamp: BrushStamp): Rect {
            val radius = stamp.size * .72f + 1f
            return Rect(stamp.x - radius, stamp.y - radius, stamp.x + radius, stamp.y + radius)
        }

        private fun bitmap(tip: BrushTip): Bitmap = Bitmap.createBitmap(tip.width, tip.height, Bitmap.Config.ARGB_8888).apply {
            val pixels = IntArray(tip.width * tip.height) { Color.argb(tip.coverage(it), 255, 255, 255) }
            setPixels(pixels, 0, tip.width, 0, 0, tip.width, tip.height)
        }
        private fun shapeBitmap(config: StampBrush, hardness: Float) = bitmap(BrushTip.shape(config, hardness))
        private fun grainBitmap(config: StampBrush) = bitmap(BrushTip.grain(config))
    }
}
