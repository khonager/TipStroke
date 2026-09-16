package dev.tipstroke.drawing.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.ink.brush.*
import androidx.ink.brush.behavior.*
import dev.tipstroke.core.model.BrushEngine
import dev.tipstroke.core.model.BrushPreset
import java.util.LinkedHashMap
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.random.Random

/** TipStroke-owned Ink families. Finished meshes are still flattened into sparse raster tiles. */
@OptIn(ExperimentalInkCustomBrushApi::class)
internal class TipStrokeInkBrushes {
    private val textures = mapOf(
        PENCIL_GRAIN_TEXTURE to pencilGrainTexture(),
        AIRBRUSH_PARTICLE_TEXTURE to airbrushParticleTexture(),
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
        val graphiteNoise = NoiseNode(0x51A7, ProgressDomain.DISTANCE_IN_MULTIPLES_OF_BRUSH_SIZE, .16f)
        val behaviors = buildList {
            add(mapped(TargetNode.Target.SIZE_MULTIPLIER, preset.pressureToSize.start, preset.pressureToSize.end, pressure))
            add(mapped(TargetNode.Target.OPACITY_MULTIPLIER, preset.pressureToOpacity.start, preset.pressureToOpacity.end, pressure))
            add(mapped(TargetNode.Target.WIDTH_MULTIPLIER, 1f, 2.8f, tilt))
            add(mapped(TargetNode.Target.HEIGHT_MULTIPLIER, 1f, .58f, tilt))
            add(mapped(TargetNode.Target.OPACITY_MULTIPLIER, 1f, .72f, tilt))
            add(mapped(TargetNode.Target.ROTATION_OFFSET_IN_RADIANS, (-PI).toFloat(), PI.toFloat(), orientation))
            if (preset.speedTaper > 0f) {
                add(mapped(TargetNode.Target.OPACITY_MULTIPLIER, 1f, 1f - preset.speedTaper * .58f, speed))
                add(mapped(TargetNode.Target.SIZE_MULTIPLIER, 1f, 1f - preset.speedTaper * .32f, speed))
            }
            add(mapped(TargetNode.Target.OPACITY_MULTIPLIER, .72f, 1f, graphiteNoise))
        }
        val tip = BrushTip.builder()
            .setScaleX(.92f)
            .setScaleY(.56f)
            .setCornerRounding(.82f)
            .setBehaviors(behaviors.map(::BrushBehavior))
            .build()
        val grain = BrushPaint.TilingTexture.builder()
            .setClientTextureId(PENCIL_GRAIN_TEXTURE)
            .setSizeX(18f)
            .setSizeY(18f)
            .setSizeUnit(BrushPaint.TextureLayer.SizeUnit.STROKE_COORDINATES)
            .setOrigin(BrushPaint.TilingTexture.Origin.FIRST_STROKE_INPUT)
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
        val scatter = .08f + (1f - preset.hardness) * .3f
        val tipScale = .48f + preset.hardness * .32f
        val behaviors = buildList {
            add(mapped(TargetNode.Target.SIZE_MULTIPLIER, preset.pressureToSize.start, preset.pressureToSize.end, pressure))
            add(mapped(TargetNode.Target.OPACITY_MULTIPLIER, preset.pressureToOpacity.start, preset.pressureToOpacity.end, pressure))
            if (preset.speedTaper > 0f) {
                add(mapped(TargetNode.Target.OPACITY_MULTIPLIER, 1f, 1f - preset.speedTaper * .5f, speed))
            }
            add(mapped(TargetNode.Target.POSITION_OFFSET_X_IN_MULTIPLES_OF_BRUSH_SIZE, -scatter, scatter, NoiseNode(0xA17, ProgressDomain.DISTANCE_IN_MULTIPLES_OF_BRUSH_SIZE, .055f)))
            add(mapped(TargetNode.Target.POSITION_OFFSET_Y_IN_MULTIPLES_OF_BRUSH_SIZE, -scatter, scatter, NoiseNode(0xB29, ProgressDomain.DISTANCE_IN_MULTIPLES_OF_BRUSH_SIZE, .061f)))
            add(mapped(TargetNode.Target.ROTATION_OFFSET_IN_RADIANS, (-PI).toFloat(), PI.toFloat(), NoiseNode(0xC31, ProgressDomain.DISTANCE_IN_MULTIPLES_OF_BRUSH_SIZE, .047f)))
            add(mapped(TargetNode.Target.OPACITY_MULTIPLIER, .58f, 1f, NoiseNode(0xD43, ProgressDomain.DISTANCE_IN_MULTIPLES_OF_BRUSH_SIZE, .038f)))
        }
        val tip = BrushTip.builder()
            .setScaleX(tipScale)
            .setScaleY(tipScale)
            .setCornerRounding(1f)
            .setParticleGapDistanceScale(preset.spacing.coerceIn(.025f, .28f))
            // A 60 Hz stationary emission rate still builds paint naturally without
            // growing an unnecessarily dense particle mesh during a long hold.
            .setParticleGapDurationMillis(16L)
            .setBehaviors(behaviors.map(::BrushBehavior))
            .build()
        val particles = BrushPaint.StampingTexture.builder()
            .setClientTextureId(AIRBRUSH_PARTICLE_TEXTURE)
            .setBlendMode(BrushPaint.TextureLayer.BlendMode.MODULATE)
            .build()
        return family(
            "Sparse radial particles with deterministic scatter and natural paint buildup.",
            tip,
            BrushPaint(listOf(particles), emptyList(), SelfOverlap.ACCUMULATE),
            7L,
        )
    }

    private fun family(comment: String, tip: BrushTip, paint: BrushPaint, smoothingMillis: Long) =
        BrushFamily.builder()
            .setCoat(BrushCoat(tip, paint))
            .setInputModel(BrushFamily.InputModel.SlidingWindowModel(smoothingMillis, 180))
            .setDeveloperComment(comment)
            .build()

    private fun mapped(target: TargetNode.Target, start: Float, end: Float, input: ValueNode) =
        TargetNode(target, start, end, input)

    private fun eased(source: ValueNode): ValueNode = ResponseNode(EasingFunction.Predefined.EASE_OUT, source)

    companion object {
        const val PENCIL_GRAIN_TEXTURE = "dev.tipstroke.texture.pencil-grain.v1"
        const val AIRBRUSH_PARTICLE_TEXTURE = "dev.tipstroke.texture.airbrush-particles.v1"

        fun pencilGrainTexture(): Bitmap {
            val size = 512
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(size * size)
            val random = Random(0x71F3)
            var previous = 0f
            for (y in 0 until size) for (x in 0 until size) {
                previous = previous * .36f + random.nextFloat() * .64f
                val paper = .28f + previous * .72f
                val fiber = if ((x * 3 + y) % 29 == 0) .62f else 1f
                val alpha = (255f * paper * fiber).toInt().coerceIn(28, 255)
                pixels[y * size + x] = Color.argb(alpha, 255, 255, 255)
            }
            bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
            return bitmap
        }

        fun airbrushParticleTexture(): Bitmap {
            val size = 512
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val random = Random(0xA1B2C3)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            repeat(2300) {
                val angle = random.nextFloat() * (PI * 2).toFloat()
                val radius = sqrt(random.nextFloat()) * size * .47f
                val normalized = radius / (size * .47f)
                val radialDensity = exp((-normalized * normalized * 2.4f).toDouble()).toFloat()
                if (random.nextFloat() <= radialDensity) {
                    val x = size / 2f + kotlin.math.cos(angle) * radius
                    val y = size / 2f + kotlin.math.sin(angle) * radius
                    paint.alpha = (28 + random.nextFloat() * 118f * radialDensity).toInt().coerceIn(12, 150)
                    canvas.drawCircle(x, y, .45f + random.nextFloat() * 1.8f, paint)
                }
            }
            return bitmap
        }
    }
}
