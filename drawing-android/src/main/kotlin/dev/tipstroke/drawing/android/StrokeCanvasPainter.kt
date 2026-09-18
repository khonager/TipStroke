package dev.tipstroke.drawing.android

import android.graphics.BlurMaskFilter
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import dev.tipstroke.core.drawing.CompletedStroke
import dev.tipstroke.core.drawing.StrokeSample
import dev.tipstroke.core.geometry.Point
import dev.tipstroke.core.model.BlendBehavior
import dev.tipstroke.core.model.BrushEngine
import dev.tipstroke.core.model.RgbaColor
import kotlin.math.*

/** Shared painter for custom wet previews and their byte-for-byte-equivalent tile commit. */
internal object StrokeCanvasPainter {
    private val pencilGrainMasks by lazy {
        val source = TipStrokeInkBrushes.pencilGrainTexture()
        val sourcePixels = IntArray(source.width * source.height)
        source.getPixels(sourcePixels, 0, source.width, 0, 0, source.width, source.height)
        // An upright HB point deposits a fairly continuous dark line. A tilted side contact
        // catches much less graphite in the paper valleys, which exposes stronger tooth.
        intArrayOf(230, 190, 145, 100).map { valleyAlpha ->
            Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).apply {
                val pixels = sourcePixels.map { pixel ->
                    Color.argb(if (Color.alpha(pixel) == 0) valleyAlpha else 255, 255, 255, 255)
                }.toIntArray()
                setPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
            }
        }.also { source.recycle() }
    }
    private val pencilGrainShaders by lazy {
        pencilGrainMasks.map { mask ->
            BitmapShader(mask, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply {
                // Paper tooth should read at graphite scale, not as conspicuous white chunks.
                setLocalMatrix(Matrix().apply { setScale(.38f, .38f) })
            }
        }
    }

    /**
     * Applies a spatial pressure mask to an already-rendered Ink tile. Ink still supplies the
     * nib geometry and Pencil texture; this makes final raster alpha deterministic on devices
     * where the experimental opacity target is visually ineffective.
     */
    fun applyPressureOpacityMask(bitmap: android.graphics.Bitmap, stroke: CompletedStroke, tileLeft: Int, tileTop: Int) {
        if (!pressureBehaviorEnabled(stroke.style.brush.pressureToOpacity) || stroke.samples.isEmpty()) return
        val mask = android.graphics.Bitmap.createBitmap(bitmap.width, bitmap.height, android.graphics.Bitmap.Config.ARGB_8888)
        val maskCanvas = Canvas(mask)
        val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            // Covers Pencil's widest tilt-expanded tip while alpha varies along its centerline.
            strokeWidth = stroke.style.sizePx * 4f
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
        }
        val samples = stroke.samples
        fun maskAlpha(sample: StrokeSample): Float = sample.pressureOpacity(stroke).coerceIn(0f, 1f)
        if (samples.size == 1) {
            val sample = samples.first()
            maskPaint.style = Paint.Style.FILL
            maskPaint.alpha = (maskAlpha(sample) * 255).roundToInt()
            maskCanvas.drawCircle(
                sample.position.x - tileLeft,
                sample.position.y - tileTop,
                stroke.style.sizePx * 2f,
                maskPaint,
            )
        } else {
            for (index in 1 until samples.size) {
                val previous = samples[index - 1]
                val current = samples[index]
                maskPaint.alpha = (((maskAlpha(previous) + maskAlpha(current)) / 2f) * 255).roundToInt()
                maskCanvas.drawLine(
                    previous.position.x - tileLeft,
                    previous.position.y - tileTop,
                    current.position.x - tileLeft,
                    current.position.y - tileTop,
                    maskPaint,
                )
            }
        }
        Canvas(bitmap).drawBitmap(mask, 0f, 0f, Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        })
        mask.recycle()
    }

    fun preparePaint(stroke: CompletedStroke) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = stroke.style.sizePx
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            style = Paint.Style.STROKE
            color = stroke.style.color.toArgb()
            alpha = (stroke.style.opacity.coerceIn(0f, 1f) * stroke.style.color.alpha * 255).roundToInt()
            xfermode = if (stroke.style.blend == BlendBehavior.ERASE) PorterDuffXfermode(PorterDuff.Mode.CLEAR) else null
            val soften = when {
                stroke.style.blend == BlendBehavior.ERASE -> 1f - stroke.style.brush.hardness
                stroke.style.brush.engine == BrushEngine.AIRBRUSH -> 1f - stroke.style.brush.hardness
                else -> 0f
            }
            if (soften > .01f) {
                maskFilter = BlurMaskFilter(stroke.style.sizePx * .42f * soften, BlurMaskFilter.Blur.NORMAL)
            }
    }

    fun draw(canvas: Canvas, stroke: CompletedStroke, paint: Paint = preparePaint(stroke)) {
        val saveCount = canvas.save()
        stroke.style.selection?.let { canvas.clipPath(it.toAndroidPath()) }
        val samples = stroke.samples
        if (stroke.style.blend == BlendBehavior.PAINT && stroke.style.brush.engine == BrushEngine.PENCIL) {
            drawPencil(canvas, stroke, paint, replaceExisting = false, capStart = true, capEnd = true)
            canvas.restoreToCount(saveCount)
            return
        }
        if (stroke.style.blend == BlendBehavior.PAINT && stroke.style.brush.engine == BrushEngine.AIRBRUSH) {
            if (pressureBehaviorEnabled(stroke.style.brush.pressureToOpacity)) {
                drawPressureResponsiveAirbrush(canvas, stroke, paint)
            } else {
                drawUnifiedAirbrushMask(canvas, stroke, paint)
            }
            canvas.restoreToCount(saveCount)
            return
        }
        if (samples.size == 1) {
            val sample = samples.first()
            paint.style = Paint.Style.FILL
            paint.alpha = (stroke.style.opacity * stroke.style.color.alpha * sample.pressureOpacity(stroke) * 255)
                .roundToInt().coerceIn(0, 255)
            canvas.drawCircle(sample.position.x, sample.position.y, stroke.style.sizePx * sample.pressureSize(stroke) / 2f, paint)
        } else {
            for (index in 1 until samples.size) {
                val previous = samples[index - 1]
                val current = samples[index]
                val pressureSize = (previous.pressureSize(stroke) + current.pressureSize(stroke)) / 2f
                paint.strokeWidth = stroke.style.sizePx * pressureSize * current.speedSize(stroke, previous)
                paint.alpha = (stroke.style.opacity * stroke.style.color.alpha * current.pressureOpacity(stroke) * 255)
                    .roundToInt().coerceIn(0, 255)
                canvas.drawLine(previous.position.x, previous.position.y, current.position.x, current.position.y, paint)
            }
        }
        canvas.restoreToCount(saveCount)
    }

    /** Draws a Pencil segment directly into its isolated sparse preview tiles. */
    internal fun drawPencilOverlay(
        canvas: Canvas,
        stroke: CompletedStroke,
        capStart: Boolean = false,
        capEnd: Boolean = false,
        skipFirstSegment: Boolean = false,
    ) {
        val saveCount = canvas.save()
        stroke.style.selection?.let { canvas.clipPath(it.toAndroidPath()) }
        drawPencil(
            canvas,
            stroke,
            preparePaint(stroke),
            replaceExisting = true,
            capStart,
            capEnd,
            skipFirstSegment,
        )
        canvas.restoreToCount(saveCount)
    }

    /**
     * Builds a continuous anisotropic ribbon from Pencil samples. Segment contact quads overlap
     * by a subpixel amount and their changing tangents are connected with small corner wedges,
     * avoiding both oval stamps and antialiased cracks. [replaceExisting] is used only on the
     * isolated live-stroke tiles; normal layer commits composite the completed ribbon once.
     */
    private fun drawPencil(
        canvas: Canvas,
        stroke: CompletedStroke,
        paint: Paint,
        replaceExisting: Boolean,
        capStart: Boolean,
        capEnd: Boolean,
        skipFirstSegment: Boolean = false,
    ) {
        val samples = buildList<StrokeSample> {
            stroke.samples.forEach { sample ->
                val previous = lastOrNull()
                if (previous == null || hypot(
                        sample.position.x - previous.position.x,
                        sample.position.y - previous.position.y,
                    ) >= .05f
                ) {
                    add(sample)
                } else {
                    this[lastIndex] = sample
                }
            }
        }
        if (samples.isEmpty()) return
        val layer = if (replaceExisting) null else canvas.saveLayer(
            stroke.bounds.left, stroke.bounds.top, stroke.bounds.right, stroke.bounds.bottom, null,
        )
        paint.style = Paint.Style.FILL
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
        paint.colorFilter = PorterDuffColorFilter(stroke.style.color.toOpaqueRgb(), PorterDuff.Mode.SRC_IN)

        fun configureGrain(sample: StrokeSample) {
            val tiltAmount = (sample.tiltRadians / (PI.toFloat() / 2f)).coerceIn(0f, 1f)
            paint.shader = pencilGrainShaders[(tiltAmount * pencilGrainShaders.lastIndex).roundToInt()]
        }

        fun drawCap(sample: StrokeSample, speedScale: Float = 1f) {
            val dynamics = pencilTipDynamics(stroke, sample, speedScale)
            paint.alpha = dynamics.alpha
            configureGrain(sample)
            val halfWidth = dynamics.width / 2f
            val halfHeight = dynamics.height / 2f
            val cap = Path().apply { addOval(RectF(
                sample.position.x - halfWidth,
                sample.position.y - halfHeight,
                sample.position.x + halfWidth,
                sample.position.y + halfHeight,
            ), Path.Direction.CW) }
            cap.transform(Matrix().apply {
                setRotate(Math.toDegrees(sample.orientationRadians.toDouble()).toFloat(), sample.position.x, sample.position.y)
            })
            canvas.drawPath(cap, paint)
        }

        if (samples.size == 1) {
            if (capStart || capEnd) drawCap(samples.first())
        } else {
            data class Section(val left: Point, val right: Point)
            data class Segment(
                val tangentX: Float,
                val tangentY: Float,
                val normalX: Float,
                val normalY: Float,
                val start: Section,
                val end: Section,
            )

            val dynamics = samples.mapIndexed { index, sample ->
                val speedScale = if (index == 0) {
                    samples[1].speedSize(stroke, sample)
                } else {
                    sample.speedSize(stroke, samples[index - 1])
                }
                pencilTipDynamics(stroke, sample, speedScale)
            }

            fun section(sample: StrokeSample, tip: PencilTipDynamics, normalX: Float, normalY: Float): Section {
                val majorX = cos(sample.orientationRadians)
                val majorY = sin(sample.orientationRadians)
                val normalOnMajor = normalX * majorX + normalY * majorY
                val normalOnMinor = normalX * -majorY + normalY * majorX
                val radius = sqrt(
                    (tip.width * .5f * normalOnMajor).pow(2) +
                        (tip.height * .5f * normalOnMinor).pow(2),
                ).coerceAtLeast(.25f)
                return Section(
                    Point(sample.position.x + normalX * radius, sample.position.y + normalY * radius),
                    Point(sample.position.x - normalX * radius, sample.position.y - normalY * radius),
                )
            }

            fun segment(index: Int): Segment {
                val previousPosition = samples[index - 1].position
                val currentPosition = samples[index].position
                val segmentX = currentPosition.x - previousPosition.x
                val segmentY = currentPosition.y - previousPosition.y
                val segmentLength = hypot(segmentX, segmentY).coerceAtLeast(.0001f)
                val tangentX = segmentX / segmentLength
                val tangentY = segmentY / segmentLength
                val normalX = -tangentY
                val normalY = tangentX
                return Segment(
                    tangentX,
                    tangentY,
                    normalX,
                    normalY,
                    section(samples[index - 1], dynamics[index - 1], normalX, normalY),
                    section(samples[index], dynamics[index], normalX, normalY),
                )
            }

            val segments = (1 until samples.size).associateWith(::segment)
            val firstSegmentIndex = if (skipFirstSegment) 2 else 1
            for (index in firstSegmentIndex until samples.size) {
                val geometry = segments.getValue(index)
                // Neighboring quads overlap by less than one pixel. This covers the internal
                // antialias fringe without creating the large protruding stamp shapes that a
                // full ellipse at every input sample would leave behind.
                val overlap = .75f
                val quad = Path().apply {
                    moveTo(
                        geometry.start.left.x - geometry.tangentX * overlap,
                        geometry.start.left.y - geometry.tangentY * overlap,
                    )
                    lineTo(
                        geometry.end.left.x + geometry.tangentX * overlap,
                        geometry.end.left.y + geometry.tangentY * overlap,
                    )
                    lineTo(
                        geometry.end.right.x + geometry.tangentX * overlap,
                        geometry.end.right.y + geometry.tangentY * overlap,
                    )
                    lineTo(
                        geometry.start.right.x - geometry.tangentX * overlap,
                        geometry.start.right.y - geometry.tangentY * overlap,
                    )
                    close()
                }
                val rgb = stroke.style.color.toOpaqueRgb()
                val gradient = LinearGradient(
                    samples[index - 1].position.x,
                    samples[index - 1].position.y,
                    samples[index].position.x,
                    samples[index].position.y,
                    Color.argb(dynamics[index - 1].alpha, Color.red(rgb), Color.green(rgb), Color.blue(rgb)),
                    Color.argb(dynamics[index].alpha, Color.red(rgb), Color.green(rgb), Color.blue(rgb)),
                    Shader.TileMode.CLAMP,
                )
                val averageTilt = (samples[index - 1].tiltRadians + samples[index].tiltRadians) * .5f
                val tiltAmount = (averageTilt / (PI.toFloat() / 2f)).coerceIn(0f, 1f)
                val grain = pencilGrainShaders[(tiltAmount * pencilGrainShaders.lastIndex).roundToInt()]
                paint.alpha = 255
                paint.colorFilter = null
                paint.shader = ComposeShader(gradient, grain, PorterDuff.Mode.DST_IN)
                canvas.drawPath(quad, paint)

                if (index >= 2) {
                    val previousGeometry = segments.getValue(index - 1)
                    val joint = samples[index - 1]
                    val join = Path().apply {
                        moveTo(
                            previousGeometry.end.left.x - previousGeometry.tangentX * overlap,
                            previousGeometry.end.left.y - previousGeometry.tangentY * overlap,
                        )
                        lineTo(
                            geometry.start.left.x + geometry.tangentX * overlap,
                            geometry.start.left.y + geometry.tangentY * overlap,
                        )
                        lineTo(joint.position.x, joint.position.y)
                        close()
                        moveTo(
                            previousGeometry.end.right.x - previousGeometry.tangentX * overlap,
                            previousGeometry.end.right.y - previousGeometry.tangentY * overlap,
                        )
                        lineTo(joint.position.x, joint.position.y)
                        lineTo(
                            geometry.start.right.x + geometry.tangentX * overlap,
                            geometry.start.right.y + geometry.tangentY * overlap,
                        )
                        close()
                    }
                    paint.shader = pencilGrainShaders[
                        ((joint.tiltRadians / (PI.toFloat() / 2f)).coerceIn(0f, 1f) * pencilGrainShaders.lastIndex)
                            .roundToInt()
                    ]
                    paint.colorFilter = PorterDuffColorFilter(rgb, PorterDuff.Mode.SRC_IN)
                    paint.alpha = dynamics[index - 1].alpha
                    canvas.drawPath(join, paint)
                }
            }
            paint.colorFilter = PorterDuffColorFilter(stroke.style.color.toOpaqueRgb(), PorterDuff.Mode.SRC_IN)
            if (capStart) drawCap(samples.first(), samples[1].speedSize(stroke, samples.first()))
            if (capEnd) drawCap(samples.last(), samples.last().speedSize(stroke, samples[samples.lastIndex - 1]))
        }
        paint.shader = null
        paint.colorFilter = null
        paint.xfermode = null
        layer?.let(canvas::restoreToCount)
    }

    internal data class PencilTipDynamics(val width: Float, val height: Float, val alpha: Int)

    internal fun pencilTipDynamics(stroke: CompletedStroke, sample: StrokeSample, speedScale: Float = 1f): PencilTipDynamics {
        val normalizedTilt = (sample.tiltRadians / (PI.toFloat() / 2f)).coerceIn(0f, 1f)
        val tiltResponse = 1f - (1f - normalizedTilt) * (1f - normalizedTilt)
        val widthMultiplier = 1f + (TipStrokeInkBrushes.PENCIL_MAX_TILT_WIDTH_MULTIPLIER - 1f) * tiltResponse
        val heightMultiplier = 1f + (TipStrokeInkBrushes.PENCIL_MAX_TILT_HEIGHT_MULTIPLIER - 1f) * tiltResponse
        val tiltOpacity = 1f + (TipStrokeInkBrushes.PENCIL_MIN_TILT_OPACITY_MULTIPLIER - 1f) * tiltResponse
        val pressureSize = sample.pressureSize(stroke)
        val edgeVariation = 1f +
            sin(sample.position.x * .071f + sample.position.y * .113f) * .035f +
            sin(sample.position.x * .029f - sample.position.y * .053f) * .018f
        val densityVariation = 1f + sin(sample.position.x * .047f + sample.position.y * .031f) * .045f
        return PencilTipDynamics(
            width = stroke.style.sizePx * TipStrokeInkBrushes.PENCIL_BASE_TIP_SCALE * pressureSize * speedScale * widthMultiplier * edgeVariation,
            height = stroke.style.sizePx * TipStrokeInkBrushes.PENCIL_BASE_TIP_SCALE * pressureSize * speedScale * heightMultiplier * edgeVariation,
            alpha = (stroke.style.opacity * stroke.style.color.alpha * sample.pressureOpacity(stroke) * tiltOpacity * densityVariation * 255f)
                .roundToInt().coerceIn(0, 255),
        )
    }

    /**
     * Draws variable-opacity airbrush segments into an isolated layer. SRC replacement inside
     * that layer prevents overlapping samples from becoming darker while pressure still varies
     * along the stroke; restoring the layer composites the result over existing paint once.
     */
    private fun drawPressureResponsiveAirbrush(canvas: Canvas, stroke: CompletedStroke, paint: Paint) {
        val samples = stroke.samples
        val padding = stroke.style.sizePx
        val bounds = stroke.bounds
        val layer = canvas.saveLayer(
            bounds.left - padding,
            bounds.top - padding,
            bounds.right + padding,
            bounds.bottom + padding,
            null,
        )
        val originalMode = paint.xfermode
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        val baseAlpha = stroke.style.opacity * stroke.style.color.alpha * 255f
        if (samples.size == 1) {
            val sample = samples.first()
            paint.style = Paint.Style.FILL
            paint.alpha = (baseAlpha * sample.pressureOpacity(stroke)).roundToInt().coerceIn(0, 255)
            canvas.drawCircle(
                sample.position.x,
                sample.position.y,
                stroke.style.sizePx * sample.pressureSize(stroke) / 2f,
                paint,
            )
        } else {
            for (index in 1 until samples.size) {
                val previous = samples[index - 1]
                val current = samples[index]
                val sizeScale = (previous.pressureSize(stroke) + current.pressureSize(stroke)) / 2f
                paint.strokeWidth = stroke.style.sizePx * sizeScale * current.speedSize(stroke, previous)
                val opacityScale = (previous.pressureOpacity(stroke) + current.pressureOpacity(stroke)) / 2f
                paint.alpha = (baseAlpha * opacityScale).roundToInt().coerceIn(0, 255)
                canvas.drawLine(previous.position.x, previous.position.y, current.position.x, current.position.y, paint)
            }
        }
        paint.xfermode = originalMode
        canvas.restoreToCount(layer)
    }

    /** Builds one filled variable-width silhouette, then applies the blur to that union once. */
    private fun drawUnifiedAirbrushMask(canvas: Canvas, stroke: CompletedStroke, paint: Paint) {
        val samples = stroke.samples
        if (samples.size == 1) {
            val sample = samples.first()
            paint.style = Paint.Style.FILL
            canvas.drawCircle(
                sample.position.x,
                sample.position.y,
                stroke.style.sizePx * sample.pressureSize(stroke) / 2f,
                paint,
            )
            return
        }
        val radii = samples.mapIndexed { index, sample ->
            val speedScale = if (index == 0) 1f else sample.speedSize(stroke, samples[index - 1])
            stroke.style.sizePx * sample.pressureSize(stroke) * speedScale / 2f
        }
        val normals = samples.indices.map { index ->
            val before = samples[(index - 1).coerceAtLeast(0)].position
            val after = samples[(index + 1).coerceAtMost(samples.lastIndex)].position
            val dx = after.x - before.x
            val dy = after.y - before.y
            val length = kotlin.math.hypot(dx, dy).coerceAtLeast(.0001f)
            Point(-dy / length, dx / length)
        }
        val path = Path().apply {
            val first = samples.first().position
            moveTo(first.x + normals.first().x * radii.first(), first.y + normals.first().y * radii.first())
            for (index in 1..samples.lastIndex) {
                val point = samples[index].position
                lineTo(point.x + normals[index].x * radii[index], point.y + normals[index].y * radii[index])
            }
            for (index in samples.lastIndex downTo 0) {
                val point = samples[index].position
                lineTo(point.x - normals[index].x * radii[index], point.y - normals[index].y * radii[index])
            }
            close()
            // Match the ribbon winding so the caps union with it instead of punching out their
            // overlapping halves and leaving detached semicircles.
            addCircle(samples.first().position.x, samples.first().position.y, radii.first(), Path.Direction.CCW)
            addCircle(samples.last().position.x, samples.last().position.y, radii.last(), Path.Direction.CCW)
        }
        paint.style = Paint.Style.FILL
        paint.alpha = (stroke.style.opacity.coerceIn(0f, 1f) * stroke.style.color.alpha * 255)
            .roundToInt().coerceIn(0, 255)
        canvas.drawPath(path, paint)
    }

    private fun StrokeSample.pressureSize(stroke: CompletedStroke) = stroke.style.brush.pressureToSize.map(pressure)
    private fun StrokeSample.pressureOpacity(stroke: CompletedStroke) = stroke.style.brush.pressureToOpacity.map(opacityPressure)
    private fun StrokeSample.speedSize(stroke: CompletedStroke, previous: StrokeSample): Float {
        val taper = stroke.style.brush.speedTaper
        if (taper <= 0f) return 1f
        val elapsedMillis = ((elapsedNanos - previous.elapsedNanos).coerceAtLeast(1L)) / 1_000_000f
        val distance = kotlin.math.hypot(position.x - previous.position.x, position.y - previous.position.y)
        val normalizedSpeed = (distance / elapsedMillis / 2.5f).coerceIn(0f, 1f)
        return 1f - normalizedSpeed * taper * .65f
    }
    private fun RgbaColor.toArgb() = Color.argb(
        (alpha * 255).roundToInt(),
        (red * 255).roundToInt(),
        (green * 255).roundToInt(),
        (blue * 255).roundToInt(),
    )
    private fun RgbaColor.toOpaqueRgb() = Color.rgb(
        (red * 255).roundToInt(),
        (green * 255).roundToInt(),
        (blue * 255).roundToInt(),
    )
}
