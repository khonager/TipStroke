package dev.tipstroke.drawing.android

import android.graphics.Bitmap
import android.graphics.Color
import androidx.ink.brush.*
import androidx.ink.brush.behavior.*
import dev.tipstroke.core.model.InkTexture
import dev.tipstroke.core.model.BrushEngine
import dev.tipstroke.core.model.BrushPreset
import dev.tipstroke.core.model.PencilTiltMode
import dev.tipstroke.core.model.PressureCurve
import dev.tipstroke.core.model.pencilFullTiltRadians
import dev.tipstroke.core.model.pencilShadeEndRadians
import java.util.LinkedHashMap
import kotlin.math.PI
import kotlin.math.roundToInt

/** TipStroke-owned Ink families. Finished meshes are still flattened into sparse raster tiles. */
@OptIn(ExperimentalInkCustomBrushApi::class)
internal class TipStrokeInkBrushes {
    private val textures = buildMap {
        put(PENCIL_GRAIN_TEXTURE, pencilGrainTexture())
        put(SOFT_INK_TEXTURE, softInkTexture())
    }
    val textureStore = object : TextureBitmapStore {
        override fun get(clientTextureId: String): Bitmap? = textures[clientTextureId]
    }
    private val families = object : LinkedHashMap<BrushPreset, BrushFamily>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<BrushPreset, BrushFamily>?) = size > 32
    }

    @Synchronized
    fun familyFor(preset: BrushPreset): BrushFamily = families[preset] ?: run {
        val family = if (preset.ink != null) customInkFamily(preset) else when (preset.engine) {
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

    private fun customInkFamily(preset: BrushPreset): BrushFamily {
        val config = requireNotNull(preset.ink)
        val pressure = SourceNode(SourceNode.Source.NORMALIZED_PRESSURE, 0f, 1f)
        val behaviors = buildList {
            if (pressureBehaviorEnabled(preset.pressureToSize)) add(mapped(TargetNode.Target.SIZE_MULTIPLIER,
                preset.pressureToSize.start, preset.pressureToSize.end, inkPressureResponse(pressure, preset.pressureToSize.exponent)))
            if (pressureBehaviorEnabled(preset.pressureToOpacity)) add(mapped(TargetNode.Target.OPACITY_MULTIPLIER,
                preset.pressureToOpacity.start, preset.pressureToOpacity.end, inkPressureResponse(pressure, preset.pressureToOpacity.exponent)))
            if (config.tiltSize > 0f) add(mapped(TargetNode.Target.SIZE_MULTIPLIER, 1f, 1f + config.tiltSize,
                SourceNode(SourceNode.Source.TILT_IN_RADIANS, 0f, 1.2f)))
            if (preset.speedTaper > 0f) add(mapped(TargetNode.Target.SIZE_MULTIPLIER, 1f, 1f - .8f * preset.speedTaper,
                SourceNode(SourceNode.Source.SPEED_IN_MULTIPLES_OF_BRUSH_SIZE_PER_SECOND, 0f, 18f)))
        }
        val accumulate = config.accumulate || config.texture == InkTexture.SOFT ||
            pressureBehaviorEnabled(preset.pressureToOpacity)
        // In the pinned native engine, continuous ACCUMULATE geometry retains the first
        // contact's size. Use particles so pressure/tilt evolve along accumulating strokes.
        val particles = config.particles || accumulate
        // Keep solid particles as native geometry. Textured quads reduced short-stroke
        // costs but made sustained full-canvas buildup substantially more expensive.
        val circular = config.rounding == 1f && config.aspectRatio == 1f
        val texturedQuad = config.texture == InkTexture.SOFT && circular
        val tip = BrushTip.builder().setScaleX(1f).setScaleY(config.aspectRatio)
            .setCornerRounding(if (texturedQuad) 0f else config.rounding).setRotationDegrees(config.rotationDegrees)
            .setParticleGapDistanceScale(if (particles) preset.spacing.coerceAtLeast(.02f) else 0f)
            .setBehaviors(behaviors.map(::BrushBehavior)).build()
        val textures = when (config.texture) {
            InkTexture.SOLID -> emptyList()
            InkTexture.GRAPHITE -> listOf(BrushPaint.TilingTexture.builder()
                .setClientTextureId(PENCIL_GRAIN_TEXTURE).setSizeX(48f).setSizeY(48f)
                .setSizeUnit(BrushPaint.TextureLayer.SizeUnit.STROKE_COORDINATES)
                .setOrigin(BrushPaint.TilingTexture.Origin.STROKE_SPACE_ORIGIN)
                .setWrapX(BrushPaint.TextureLayer.Wrap.REPEAT).setWrapY(BrushPaint.TextureLayer.Wrap.REPEAT)
                .setBlendMode(BrushPaint.TextureLayer.BlendMode.MODULATE).build())
            InkTexture.SOFT -> listOf(BrushPaint.StampingTexture.builder().setClientTextureId(SOFT_INK_TEXTURE)
                .setBlendMode(BrushPaint.TextureLayer.BlendMode.MODULATE).build())
        }
        // DISCARD cannot express opacity dynamics or stamping textures. Keep those
        // brushes on the mesh renderer for both authoring and permanent tile rendering.
        val overlap = if (accumulate) SelfOverlap.ACCUMULATE else SelfOverlap.DISCARD
        return family("TipStroke Jetpack Ink comparison brush", tip, BrushPaint(textures, emptyList(), overlap),
            (preset.stabilization * 30f).roundToInt().coerceAtLeast(1).toLong())
    }

    private fun inkPressureResponse(source: ValueNode, exponent: Float): ValueNode = ResponseNode(
        EasingFunction.Linear((0..32).map { index ->
            val x = (index / 32f).let { it * it }
            androidx.ink.geometry.ImmutableVec(x, Math.pow(x.toDouble(), exponent.toDouble()).toFloat())
        }), source,
    )

    private fun pencilFamily(preset: BrushPreset): BrushFamily {
        val rawPressure = SourceNode(SourceNode.Source.NORMALIZED_PRESSURE, 0f, 1f)
        val pressure = eased(rawPressure)
        val tilt = when (preset.pencilTiltMode) {
            PencilTiltMode.GRADUAL -> eased(SourceNode(
                SourceNode.Source.TILT_IN_RADIANS,
                PENCIL_TILT_DEAD_ZONE_RADIANS,
                preset.pencilFullTiltRadians(),
            ))
            PencilTiltMode.SHADING_SWITCH -> SourceNode(
                SourceNode.Source.TILT_IN_RADIANS,
                preset.pencilShadeStartRadians,
                preset.pencilShadeEndRadians(),
            )
        }
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
            if (pencilShadeSizeBehaviorEnabled(preset)) {
                add(mapped(
                    TargetNode.Target.WIDTH_MULTIPLIER,
                    1f,
                    1f + (PENCIL_MAX_TILT_WIDTH_MULTIPLIER - 1f) * preset.pencilShadeSize,
                    tilt,
                ))
                add(mapped(
                    TargetNode.Target.HEIGHT_MULTIPLIER,
                    1f,
                    1f + (PENCIL_MAX_TILT_HEIGHT_MULTIPLIER - 1f) * preset.pencilShadeSize,
                    tilt,
                ))
            }
            if (pencilShadeOpacityBehaviorEnabled(preset)) {
                add(mapped(TargetNode.Target.OPACITY_MULTIPLIER, 1f, preset.pencilShadeOpacity, tilt))
            }
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
            .setScaleX(PENCIL_BASE_TIP_SCALE * preset.pencilPointSize)
            .setScaleY(PENCIL_BASE_TIP_SCALE * preset.pencilPointSize)
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
        val sizePressure = pressureResponse(rawPressure, preset.pressureToSize.exponent)
        val opacityPressure = pressureResponse(rawPressure, preset.pressureToOpacity.exponent)
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
                add(mapped(TargetNode.Target.SIZE_MULTIPLIER, preset.pressureToSize.start, preset.pressureToSize.end, sizePressure))
            }
            if (pressureBehaviorEnabled(preset.pressureToOpacity)) {
                add(mapped(TargetNode.Target.OPACITY_MULTIPLIER, preset.pressureToOpacity.start, preset.pressureToOpacity.end, opacityPressure))
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
        val pressure = pressureResponse(
            SourceNode(SourceNode.Source.NORMALIZED_PRESSURE, 0f, 1f),
            preset.pressureToSize.exponent,
        )
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

    private fun pressureResponse(source: ValueNode, exponent: Float): ValueNode = ResponseNode(
        when {
            exponent < .78f -> EasingFunction.Predefined.EASE_OUT
            exponent < 1.3f -> EasingFunction.Predefined.LINEAR
            else -> EasingFunction.Predefined.EASE_IN
        },
        source,
    )

    companion object {
        private const val SOFT_INK_TEXTURE = "dev.tipstroke.texture.ink-soft.v1"
        private fun softInkTexture(): Bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply {
            val pixels = IntArray(128 * 128) { index ->
                val x = (index % 128 + .5f) / 64f - 1f
                val y = (index / 128 + .5f) / 64f - 1f
                val r2 = x * x + y * y
                val alpha = if (r2 >= 1f) 0 else ((kotlin.math.exp(-5f * r2) - kotlin.math.exp(-5f)) /
                    (1f - kotlin.math.exp(-5f)) * 255).roundToInt().coerceIn(0, 255)
                Color.argb(alpha, 255, 255, 255)
            }
            setPixels(pixels, 0, 128, 0, 0, 128, 128)
        }

        const val PENCIL_GRAIN_TEXTURE = "dev.tipstroke.texture.pencil-grain.v4"
        internal const val PENCIL_BASE_TIP_SCALE = .18f
        internal const val PENCIL_TILT_DEAD_ZONE_RADIANS = BrushPreset.PENCIL_TILT_DEAD_ZONE_RADIANS
        internal const val PENCIL_MAX_TILT_WIDTH_MULTIPLIER = 10f
        internal const val PENCIL_MAX_TILT_HEIGHT_MULTIPLIER = 6.5f
        internal const val PENCIL_MIN_TILT_OPACITY_MULTIPLIER = .62f

        fun pencilGrainTexture(): Bitmap {
            val tip = dev.tipstroke.core.drawing.Graphite.grain()
            val pixels = IntArray(tip.width * tip.height) { Color.argb(tip.coverage(it), 255, 255, 255) }
            return Bitmap.createBitmap(pixels, tip.width, tip.height, Bitmap.Config.ARGB_8888)
        }
    }
}

internal fun pressureBehaviorEnabled(curve: PressureCurve): Boolean = curve.start != curve.end

/** A zero boost intentionally keeps upright and tilted Pencil contacts the same size. */
internal fun pencilShadeSizeBehaviorEnabled(preset: BrushPreset): Boolean = preset.pencilShadeSize != 0f

/** Ink rejects target modifier ranges whose endpoints are equal, including the valid 100% UI setting. */
internal fun pencilShadeOpacityBehaviorEnabled(preset: BrushPreset): Boolean = preset.pencilShadeOpacity != 1f
