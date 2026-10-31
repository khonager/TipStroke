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
    private val grain = grainBitmap(config)
    private val shapeShader = BitmapShader(shape, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    private val grainShader = BitmapShader(grain, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        shader = ComposeShader(shapeShader, grainShader, PorterDuff.Mode.DST_IN)
    }
    private val hsv = FloatArray(3).also { Color.colorToHSV(Color.rgb((style.color.red * 255).roundToInt(), (style.color.green * 255).roundToInt(), (style.color.blue * 255).roundToInt()), it) }
    private val tint = FloatArray(3)
    private val matrix = Matrix()

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
            shapeShader.setLocalMatrix(matrix)
            matrix.reset()
            matrix.setScale(config.grainScale / grain.width, config.grainScale / grain.height)
            if (config.grainPlacement == GrainPlacement.STAMP) matrix.postTranslate(stamp.x - stamp.size / 2f, stamp.y - stamp.size / 2f)
            grainShader.setLocalMatrix(matrix)
            tint[0] = (hsv[0] + stamp.hue + 360f) % 360f
            tint[1] = (hsv[1] + stamp.saturation).coerceIn(0f, 1f)
            tint[2] = hsv[2]
            paint.colorFilter = PorterDuffColorFilter(Color.HSVToColor(tint), PorterDuff.Mode.SRC_IN)
            paint.alpha = (stamp.alpha * 255).roundToInt()
            canvas.drawRect(bounds.left, bounds.top, bounds.right, bounds.bottom, paint)
        }
        canvas.restoreToCount(saved)
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

    override fun close() { shape.recycle(); grain.recycle() }

    companion object {
        fun bounds(stamp: BrushStamp): Rect {
            val radius = stamp.size * .72f + 1f
            return Rect(stamp.x - radius, stamp.y - radius, stamp.x + radius, stamp.y + radius)
        }

        private fun shapeBitmap(config: StampBrush, hardness: Float): Bitmap {
            val texture = config.shapeTexture.takeIf { config.shape == StampShape.IMAGE }
            val width = texture?.width ?: 128
            val height = texture?.height ?: 128
            // Transparent border makes CLAMP safe for rotated arbitrary sources on API 29.
            val result = Bitmap.createBitmap(width + 2, height + 2, Bitmap.Config.ARGB_8888)
            val pixels = IntArray((width + 2) * (height + 2))
            for (y in 0 until height) for (x in 0 until width) {
                val nx = (x + .5f) / width * 2f - 1f
                val ny = (y + .5f) / height * 2f - 1f
                val radius = when (config.shape) {
                    StampShape.SQUARE -> max(abs(nx), abs(ny))
                    StampShape.OVAL -> hypot(nx, ny * 2f)
                    else -> hypot(nx, ny)
                }
                val soft = ((1f - radius) / (1f - hardness).coerceAtLeast(.015f)).coerceIn(0f, 1f)
                val alpha = texture?.coverage(x, y) ?: if (config.shape == StampShape.BRISTLES) {
                    (255 * soft * if ((x * 17 + y / 12) % 13 < 6) 1f else .08f).roundToInt()
                } else (255 * soft).roundToInt()
                pixels[(y + 1) * (width + 2) + x + 1] = Color.argb(alpha, 255, 255, 255)
            }
            result.setPixels(pixels, 0, width + 2, 0, 0, width + 2, height + 2)
            return result
        }

        private fun grainBitmap(config: StampBrush): Bitmap {
            val texture = config.grainTexture.takeIf { config.grain == StampGrain.IMAGE }
            val width = texture?.width ?: 64
            val height = texture?.height ?: 64
            return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                val pixels = IntArray(width * height) { index ->
                    val x = index % width; val y = index / width
                    val noise = (((index * 1103515245 + 12345) ushr 16) and 255) / 255f
                    val value = when (config.grain) {
                        StampGrain.NONE -> 1f
                        StampGrain.NOISE -> noise
                        StampGrain.PAPER -> (.25f + .5f * noise + .25f * sin(x * .9f) * sin(y * .7f)).coerceIn(0f, 1f)
                        StampGrain.LINES -> if (y % 8 < 3) .15f else 1f
                        StampGrain.IMAGE -> requireNotNull(texture).coverage(x, y) / 255f
                    }
                    Color.argb(((1f - config.grainDepth + config.grainDepth * value) * 255).roundToInt(), 255, 255, 255)
                }
                setPixels(pixels, 0, width, 0, 0, width, height)
            }
        }
    }
}
