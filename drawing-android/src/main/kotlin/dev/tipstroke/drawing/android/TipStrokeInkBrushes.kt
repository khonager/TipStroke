package dev.tipstroke.drawing.android

import android.graphics.Bitmap
import android.graphics.Color
import androidx.ink.brush.*
import androidx.ink.brush.behavior.*
import dev.tipstroke.core.model.BrushEngine
import dev.tipstroke.core.model.BrushPreset
import dev.tipstroke.core.model.PressureCurve
import java.util.LinkedHashMap
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** TipStroke-owned Ink families. Finished meshes are still flattened into sparse raster tiles. */
@OptIn(ExperimentalInkCustomBrushApi::class)
internal class TipStrokeInkBrushes {
    private val textures = buildMap {
        put(PENCIL_GRAIN_TEXTURE, pencilGrainTexture())
    }
    val textureStore = object : TextureBitmapStore {
        override fun get(clientTextureId: String): Bitmap? = textures[clientTextureId]
    }
    private val families = object : LinkedHashMap<BrushPreset, BrushFamily>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<BrushPreset, BrushFamily>?) = size > 32
    }

    @Synchronized
    fun familyFor(preset: BrushPreset): BrushFamily = families[preset] ?: run {
        val family = when (preset.engine) {
            BrushEngine.PENCIL -> pencilFamily(preset)
            BrushEngine.INK -> inkFamily(preset)
            BrushEngine.AIRBRUSH -> airbrushFamily(preset)
        }
        families[preset] = family
        family
    }

    fun prewarm() {
        familyFor(BrushPreset.Pencil)
        familyFor(BrushPreset.Ink)
    }

    private fun pencilFamily(preset: BrushPreset): BrushFamily {
        val rawPressure = SourceNode(SourceNode.Source.NORMALIZED_PRESSURE, 0f, 1f)
        val pressure = eased(rawPressure)
        val tilt = eased(SourceNode(SourceNode.Source.TILT_IN_RADIANS, 0f, (PI / 2).toFloat()))
        val orientation = SourceNode(SourceNode.Source.ORIENTATION_ABOUT_ZERO_IN_RADIANS, (-PI).toFloat(), PI.toFloat())
        val speed = eased(SourceNode(SourceNode.Source.SPEED_IN_MULTIPLES_OF_BRUSH_SIZE_PER_SECOND, 0f, 18f))
        val edgeWidthNoise = NoiseNode(0x51A7, ProgressDomain.DISTANCE_IN_MULTIPLES_OF_BRUSH_SIZE, .34f)
        val edgePositionNoise = NoiseNode(0x27C1, ProgressDomain.DISTANCE_IN_MULTIPLES_OF_BRUSH_SIZE, .21f)
        val behaviors = buildList {
            if (pressureBehaviorEnabled(preset.pressureToSize)) {
                add(mapped(TargetNode.Target.SIZE_MULTIPLIER, preset.pressureToSize.start, preset.pressureToSize.end, pressure))
            }
            if (pressureBehaviorEnabled(preset.pressureToOpacity)) {
                add(mapped(TargetNode.Target.OPACITY_MULTIPLIER, preset.pressureToOpacity.start, preset.pressureToOpacity.end, rawPressure))
            }
            add(mapped(TargetNode.Target.WIDTH_MULTIPLIER, 1f, PENCIL_MAX_TILT_WIDTH_MULTIPLIER, tilt))
            add(mapped(TargetNode.Target.HEIGHT_MULTIPLIER, 1f, PENCIL_MIN_TILT_HEIGHT_MULTIPLIER, tilt))
            add(mapped(TargetNode.Target.OPACITY_MULTIPLIER, 1f, PENCIL_MIN_TILT_OPACITY_MULTIPLIER, tilt))
            add(mapped(TargetNode.Target.ROTATION_OFFSET_IN_RADIANS, (-PI).toFloat(), PI.toFloat(), orientation))
            if (preset.speedTaper > 0f) {
                add(mapped(TargetNode.Target.OPACITY_MULTIPLIER, 1f, 1f - preset.speedTaper * .58f, speed))
                add(mapped(TargetNode.Target.SIZE_MULTIPLIER, 1f, 1f - preset.speedTaper * .32f, speed))
            }
            // Real graphite does not leave a perfectly machined outline. Keep both variations
            // small and smoothly correlated so they roughen the edge without spraying marks
            // outside the intended stroke.
            add(mapped(TargetNode.Target.SIZE_MULTIPLIER, .94f, 1.06f, edgeWidthNoise))
            add(mapped(TargetNode.Target.POSITION_OFFSET_LATERAL_IN_MULTIPLES_OF_BRUSH_SIZE, -.012f, .012f, edgePositionNoise))
        }
        val tip = BrushTip.builder()
            .setScaleX(PENCIL_BASE_TIP_SCALE)
            .setScaleY(PENCIL_BASE_TIP_SCALE)
            .setCornerRounding(1f)
            .setBehaviors(behaviors.map(::BrushBehavior))
            .build()
        val grain = BrushPaint.TilingTexture.builder()
            .setClientTextureId(PENCIL_GRAIN_TEXTURE)
            .setSizeX(72f)
            .setSizeY(72f)
            .setSizeUnit(BrushPaint.TextureLayer.SizeUnit.STROKE_COORDINATES)
            .setOrigin(BrushPaint.TilingTexture.Origin.STROKE_SPACE_ORIGIN)
            .setWrapX(BrushPaint.TextureLayer.Wrap.MIRROR)
            .setWrapY(BrushPaint.TextureLayer.Wrap.MIRROR)
            .setBlendMode(BrushPaint.TextureLayer.BlendMode.MODULATE)
            .build()
        return family(
            "Textured graphite pencil with pressure, speed, tilt, orientation, and grain response.",
            tip,
            BrushPaint(listOf(grain), emptyList(), SelfOverlap.DISCARD),
            9L,
        )
    }

    private fun inkFamily(preset: BrushPreset): BrushFamily {
        val rawPressure = SourceNode(SourceNode.Source.NORMALIZED_PRESSURE, 0f, 1f)
        val pressure = eased(rawPressure)
        val start = ResponseNode(
            EasingFunction.Predefined.EASE_OUT,
            SourceNode(SourceNode.Source.DISTANCE_TRAVELED_IN_MULTIPLES_OF_BRUSH_SIZE, 0f, 1.15f),
        )
        val end = ResponseNode(
            EasingFunction.Predefined.EASE_OUT,
            SourceNode(SourceNode.Source.DISTANCE_REMAINING_IN_MULTIPLES_OF_BRUSH_SIZE, 0f, 1.05f),
        )
        val speed = eased(SourceNode(SourceNode.Source.SPEED_IN_MULTIPLES_OF_BRUSH_SIZE_PER_SECOND, 0f, 16f))
        val behaviors = buildList {
            if (pressureBehaviorEnabled(preset.pressureToSize)) {
                add(mapped(TargetNode.Target.SIZE_MULTIPLIER, preset.pressureToSize.start, preset.pressureToSize.end, pressure))
            }
            if (pressureBehaviorEnabled(preset.pressureToOpacity)) {
                add(mapped(TargetNode.Target.OPACITY_MULTIPLIER, preset.pressureToOpacity.start, preset.pressureToOpacity.end, rawPressure))
            }
            // Baskerville-style entry and exit points stay pointed even when pressure is steady.
            add(mapped(TargetNode.Target.SIZE_MULTIPLIER, .07f, 1f, start))
            add(mapped(TargetNode.Target.SIZE_MULTIPLIER, .05f, 1f, end))
            if (preset.speedTaper > 0f) {
                add(mapped(TargetNode.Target.SIZE_MULTIPLIER, 1f, 1f - preset.speedTaper * .28f, speed))
            }
        }
        val tip = BrushTip.builder()
            .setScaleX(.82f)
            .setScaleY(.82f)
            .setCornerRounding(1f)
            .setBehaviors(behaviors.map(::BrushBehavior))
            .build()
        return family(
            "Smooth pressure ink with fine entry and exit tapers.",
            tip,
            BrushPaint(emptyList(), emptyList(), SelfOverlap.DISCARD),
            12L,
        )
    }

    private fun airbrushFamily(preset: BrushPreset): BrushFamily {
        val pressure = eased(SourceNode(SourceNode.Source.NORMALIZED_PRESSURE, 0f, 1f))
        val behaviors = if (pressureBehaviorEnabled(preset.pressureToSize)) {
            listOf(BrushBehavior(mapped(
                TargetNode.Target.SIZE_MULTIPLIER,
                preset.pressureToSize.start,
                preset.pressureToSize.end,
                pressure,
            )))
        } else {
            emptyList()
        }
        val tip = BrushTip.builder()
            .setScaleX(1f)
            .setScaleY(1f)
            .setCornerRounding(1f)
            .setBehaviors(behaviors)
            .build()
        return family(
            "Solid fallback tip; production Airbrush uses the native raster blur path.",
            tip,
            BrushPaint(emptyList(), emptyList(), SelfOverlap.DISCARD),
            7L,
        )
    }

    private fun family(comment: String, tip: BrushTip, paint: BrushPaint, smoothingMillis: Long) =
        family(comment, listOf(BrushCoat(tip, paint)), smoothingMillis)

    private fun family(comment: String, coats: List<BrushCoat>, smoothingMillis: Long) =
        BrushFamily.builder()
            .setCoats(coats)
            .setInputModel(BrushFamily.InputModel.SlidingWindowModel(smoothingMillis, 180))
            .setDeveloperComment(comment)
            .build()

    private fun mapped(target: TargetNode.Target, start: Float, end: Float, input: ValueNode) =
        TargetNode(target, start, end, input)

    private fun eased(source: ValueNode): ValueNode = ResponseNode(EasingFunction.Predefined.EASE_OUT, source)

    companion object {
        const val PENCIL_GRAIN_TEXTURE = "dev.tipstroke.texture.pencil-grain.v3"
        internal const val PENCIL_BASE_TIP_SCALE = .58f
        internal const val PENCIL_MAX_TILT_WIDTH_MULTIPLIER = 4.2f
        internal const val PENCIL_MIN_TILT_HEIGHT_MULTIPLIER = .42f
        internal const val PENCIL_MIN_TILT_OPACITY_MULTIPLIER = .72f

        fun pencilGrainTexture(): Bitmap {
            val size = 256
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(size * size)
            val coarse = valueNoiseGrid(size, 19, 0x71F3)
            val medium = valueNoiseGrid(size, 7, 0x32B1)
            val tooth = valueNoiseGrid(size, 3, 0x19D7)
            for (y in 0 until size) for (x in 0 until size) {
                val index = y * size + x
                val fiber = sin(y * .24f + sin(x * .041f) * 1.9f) * .035f
                val paper = .74f + coarse[index] * .08f + medium[index] * .13f + tooth[index] * .1f + fiber
                // Broad, smoothly clustered valleys leave paper showing through. This reads as
                // graphite tooth rather than independent black/white television-static pixels.
                val valley = ((-.3f - medium[index]) / .7f).coerceIn(0f, 1f)
                val graphiteCoverage = paper - valley * valley * .4f
                // Graphite either catches on the paper tooth or leaves it exposed. Encoding
                // partial alpha throughout the texture made a 100% pencil look washed out;
                // binary coverage keeps deposited graphite fully opaque while preserving gaps.
                val alpha = if (graphiteCoverage >= .62f) 255 else 0
                pixels[y * size + x] = Color.argb(alpha, 255, 255, 255)
            }
            bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
            return bitmap
        }

        private fun valueNoiseGrid(size: Int, cellSize: Int, seed: Int): FloatArray {
            val columns = size / cellSize + 2
            val rows = size / cellSize + 2
            val random = Random(seed)
            val values = FloatArray(columns * rows) { random.nextFloat() * 2f - 1f }
            return FloatArray(size * size) { index ->
                val x = index % size
                val y = index / size
                val gridX = x / cellSize
                val gridY = y / cellSize
                val localX = smooth((x % cellSize) / cellSize.toFloat())
                val localY = smooth((y % cellSize) / cellSize.toFloat())
                val top = lerp(values[gridY * columns + gridX], values[gridY * columns + gridX + 1], localX)
                val bottom = lerp(values[(gridY + 1) * columns + gridX], values[(gridY + 1) * columns + gridX + 1], localX)
                lerp(top, bottom, localY)
            }
        }

        private fun smooth(value: Float) = value * value * (3f - 2f * value)
        private fun lerp(start: Float, end: Float, amount: Float) = start + (end - start) * amount
    }
}

internal fun pressureBehaviorEnabled(curve: PressureCurve): Boolean = curve.start != curve.end
