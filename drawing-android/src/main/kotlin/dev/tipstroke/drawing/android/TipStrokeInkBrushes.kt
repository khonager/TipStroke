package dev.tipstroke.drawing.android

import android.graphics.Bitmap
import android.graphics.Color
import androidx.ink.brush.*
import androidx.ink.brush.behavior.*
import dev.tipstroke.core.model.BrushEngine
import dev.tipstroke.core.model.BrushPreset
import java.util.LinkedHashMap
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** TipStroke-owned Ink families. Finished meshes are still flattened into sparse raster tiles. */
@OptIn(ExperimentalInkCustomBrushApi::class)
internal class TipStrokeInkBrushes {
    private val textures = mapOf(
        PENCIL_GRAIN_TEXTURE to pencilGrainTexture(),
        AIRBRUSH_OUTER_TEXTURE to airbrushParticleTexture(0xA1B2C3, .08f),
        AIRBRUSH_CORE_TEXTURE to airbrushParticleTexture(0xB2C3D4, .2f),
    )
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
            BrushEngine.INK -> StockBrushes.pressurePen()
            BrushEngine.AIRBRUSH -> airbrushFamily(preset)
        }
        families[preset] = family
        family
    }

    fun prewarm() {
        familyFor(BrushPreset.Pencil)
        familyFor(BrushPreset.Ink)
        familyFor(BrushPreset.Airbrush)
    }

    private fun pencilFamily(preset: BrushPreset): BrushFamily {
        val pressure = eased(SourceNode(SourceNode.Source.NORMALIZED_PRESSURE, 0f, 1f))
        val tilt = eased(SourceNode(SourceNode.Source.TILT_IN_RADIANS, 0f, (PI / 2).toFloat()))
        val orientation = SourceNode(SourceNode.Source.ORIENTATION_ABOUT_ZERO_IN_RADIANS, (-PI).toFloat(), PI.toFloat())
        val speed = eased(SourceNode(SourceNode.Source.SPEED_IN_MULTIPLES_OF_BRUSH_SIZE_PER_SECOND, 0f, 18f))
        val graphiteNoise = NoiseNode(0x51A7, ProgressDomain.DISTANCE_IN_MULTIPLES_OF_BRUSH_SIZE, .9f)
        val behaviors = buildList {
            add(mapped(TargetNode.Target.SIZE_MULTIPLIER, preset.pressureToSize.start, preset.pressureToSize.end, pressure))
            add(mapped(TargetNode.Target.OPACITY_MULTIPLIER, preset.pressureToOpacity.start, preset.pressureToOpacity.end, pressure))
            add(mapped(TargetNode.Target.WIDTH_MULTIPLIER, 1f, 3.15f, tilt))
            add(mapped(TargetNode.Target.HEIGHT_MULTIPLIER, 1f, .52f, tilt))
            add(mapped(TargetNode.Target.OPACITY_MULTIPLIER, 1f, .64f, tilt))
            add(mapped(TargetNode.Target.ROTATION_OFFSET_IN_RADIANS, (-PI).toFloat(), PI.toFloat(), orientation))
            if (preset.speedTaper > 0f) {
                add(mapped(TargetNode.Target.OPACITY_MULTIPLIER, 1f, 1f - preset.speedTaper * .58f, speed))
                add(mapped(TargetNode.Target.SIZE_MULTIPLIER, 1f, 1f - preset.speedTaper * .32f, speed))
            }
            add(mapped(TargetNode.Target.OPACITY_MULTIPLIER, .9f, 1f, graphiteNoise))
        }
        val tip = BrushTip.builder()
            .setScaleX(.72f)
            .setScaleY(.72f)
            .setCornerRounding(1f)
            .setBehaviors(behaviors.map(::BrushBehavior))
            .build()
        val grain = BrushPaint.TilingTexture.builder()
            .setClientTextureId(PENCIL_GRAIN_TEXTURE)
            .setSizeX(96f)
            .setSizeY(96f)
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

    private fun airbrushFamily(preset: BrushPreset): BrushFamily {
        val pressure = eased(SourceNode(SourceNode.Source.NORMALIZED_PRESSURE, 0f, 1f))
        val speed = eased(SourceNode(SourceNode.Source.SPEED_IN_MULTIPLES_OF_BRUSH_SIZE_PER_SECOND, 0f, 12f))
        val behaviors = buildList {
            add(mapped(TargetNode.Target.SIZE_MULTIPLIER, preset.pressureToSize.start, preset.pressureToSize.end, pressure))
            add(mapped(TargetNode.Target.OPACITY_MULTIPLIER, preset.pressureToOpacity.start, preset.pressureToOpacity.end, pressure))
            add(mapped(TargetNode.Target.OPACITY_MULTIPLIER, 1f, .38f, speed))
            if (preset.speedTaper > 0f) {
                add(mapped(TargetNode.Target.OPACITY_MULTIPLIER, 1f, 1f - preset.speedTaper * .5f, speed))
            }
            add(mapped(TargetNode.Target.OPACITY_MULTIPLIER, .82f, 1f, NoiseNode(0xD43, ProgressDomain.DISTANCE_IN_MULTIPLES_OF_BRUSH_SIZE, .24f)))
        }
        fun coat(scale: Float, textureId: String) = BrushCoat(
            BrushTip.builder()
                .setScaleX(scale)
                .setScaleY(scale)
                .setCornerRounding(1f)
                .setBehaviors(behaviors.map(::BrushBehavior))
                .build(),
            BrushPaint(
                listOf(BrushPaint.TilingTexture.builder()
                    .setClientTextureId(textureId)
                    .setSizeX(128f)
                    .setSizeY(128f)
                    .setSizeUnit(BrushPaint.TextureLayer.SizeUnit.STROKE_COORDINATES)
                    .setOrigin(BrushPaint.TilingTexture.Origin.STROKE_SPACE_ORIGIN)
                    .setWrapX(BrushPaint.TextureLayer.Wrap.MIRROR)
                    .setWrapY(BrushPaint.TextureLayer.Wrap.MIRROR)
                    .setBlendMode(BrushPaint.TextureLayer.BlendMode.MODULATE)
                    .build()),
                emptyList(),
                // CanvasStrokeRenderer in 1.1.0-alpha08 currently drops custom
                // ACCUMULATE coats entirely; DISCARD renders identically live and final.
                SelfOverlap.DISCARD,
            ),
        )
        val coreScale = .24f + preset.hardness * .5f
        return family(
            "Two-scale pigment field with a sparse halo, denser core, and speed-sensitive deposition.",
            listOf(coat(1f, AIRBRUSH_OUTER_TEXTURE), coat(coreScale, AIRBRUSH_CORE_TEXTURE)),
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
        const val PENCIL_GRAIN_TEXTURE = "dev.tipstroke.texture.pencil-grain.v1"
        const val AIRBRUSH_OUTER_TEXTURE = "dev.tipstroke.texture.airbrush-outer.v2"
        const val AIRBRUSH_CORE_TEXTURE = "dev.tipstroke.texture.airbrush-core.v2"

        fun pencilGrainTexture(): Bitmap {
            val size = 256
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(size * size)
            val coarse = valueNoiseGrid(size, 18, 0x71F3)
            val fine = valueNoiseGrid(size, 6, 0x32B1)
            val toothNoise = valueNoiseGrid(size, 2, 0x19D7)
            for (y in 0 until size) for (x in 0 until size) {
                val fiber = sin(y * .21f + sin(x * .035f) * 1.7f) * .022f
                val tooth = .78f + coarse[y * size + x] * .09f + fine[y * size + x] * .07f +
                    toothNoise[y * size + x] * .035f + fiber
                val alpha = (255f * tooth.coerceIn(.54f, .98f)).toInt()
                pixels[y * size + x] = Color.argb(alpha, 255, 255, 255)
            }
            bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
            return bitmap
        }

        fun airbrushParticleTexture(seed: Int, density: Float): Bitmap {
            val size = 256
            val random = Random(seed)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(size * size)
            for (index in pixels.indices) {
                if (random.nextFloat() < density) {
                    val strength = .45f + random.nextFloat() * .5f
                    pixels[index] = Color.argb((strength * 255).toInt(), 255, 255, 255)
                    if (random.nextFloat() < .08f && index + 1 < pixels.size) {
                        pixels[index + 1] = Color.argb((strength * 180).toInt(), 255, 255, 255)
                    }
                }
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
