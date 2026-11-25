package dev.tipstroke.core.editor

import dev.tipstroke.core.model.*
import dev.tipstroke.core.drawing.*
import dev.tipstroke.core.geometry.Point

/** Coarse immutable settings handed to native engines at a stroke boundary.
 * Uses the same presets, curves and tilt rules as Android; contains no UI or platform types.
 */
class NativeBrush(val preset: BrushPreset) {
    val id: String get() = preset.id.value
    val name: String get() = preset.displayName
    val engine: String get() = preset.engine.name
    val size: Float get() = preset.baseSizePx
    val opacity: Float get() = preset.opacity
    val hardness: Float get() = preset.hardness
    val spacing: Float get() = preset.spacing
    fun sizeResponse(pressure: Float): Float = preset.pressureToSize.map(pressure)
    fun opacityResponse(pressure: Float): Float = preset.pressureToOpacity.map(pressure)
    fun tiltResponse(radians: Float): Float = preset.pencilTiltResponse(radians)
    val pencilPointSize: Float get() = preset.pencilPointSize
    val pencilShadeSize: Float get() = preset.pencilShadeSize
    val pencilShadeOpacity: Float get() = preset.pencilShadeOpacity

    val usesStamps: Boolean get() = preset.stamp != null || preset.ink != null
    // Particle brushes and authored shape/grain tips retain their explicit dab placement.
    val usesSweptTip: Boolean = when {
        preset.ink != null -> with(preset.ink) {
            !particles && !accumulate && texture == InkTexture.SOLID && rounding == 1f
        }
        preset.stamp == null -> preset.engine == BrushEngine.INK
        else -> preset.id == BrushPreset.Calligraphy.id &&
            preset.spacing <= .15f && with(preset.stamp) {
                this == StampBrush(aspectRatio = aspectRatio, rotationDegrees = rotationDegrees, flow = flow)
            }
    }
    val sweepAspect = preset.ink?.aspectRatio ?: preset.stamp?.aspectRatio ?: 1f
    val sweepRotation = preset.ink?.rotationDegrees ?: preset.stamp?.rotationDegrees ?: 0f
    val sweepFlow = preset.ink?.flow ?: preset.stamp?.flow ?: 1f
    val sweepTilt = preset.ink?.tiltSize ?: 0f

    private fun stampPreset(): BrushPreset {
        if (preset.stamp != null) return preset
        val ink = preset.ink ?: return preset.copy(stamp = StampBrush())
        return preset.copy(ink = null, stamp = StampBrush(aspectRatio = ink.aspectRatio,
            rotationDegrees = ink.rotationDegrees, flow = ink.flow, tiltSize = ink.tiltSize,
            grain = if (ink.texture == InkTexture.GRAPHITE) StampGrain.PAPER else StampGrain.NONE),
            hardness = if (ink.texture == InkTexture.SOFT) .04f else preset.hardness)
    }
    fun asEraser(): NativeBrush = if (usesSweptTip) this else NativeBrush(eraserPreset())

    /** Raster mask used for destination-out strokes on both hosts. */
    fun eraserPreset(): BrushPreset {
        if (preset.stamp != null) return preset
        val base = stampPreset()
        val config = requireNotNull(base.stamp)
        val ink = preset.ink
        if (ink != null) {
            val mask = BrushTip.inkShape(ink)
            return base.copy(stamp = config.copy(shape = StampShape.IMAGE, shapeTexture = BrushTexture(
                mask.width, mask.height, ByteArray(mask.width * mask.height) { mask.coverage(it).toByte() })))
        }
        return when (preset.engine) {
            BrushEngine.PENCIL -> base.copy(stamp = config.copy(shape = StampShape.OVAL,
                grain = StampGrain.PAPER, grainDepth = preset.pencilGrain))
            else -> base
        }
    }
    fun shape(): BrushTip = preset.ink?.let { BrushTip.inkShape(it) } ?: BrushTip.shape(requireNotNull(stampPreset().stamp), stampPreset().hardness)
    fun grain(): BrushTip = if (preset.ink?.texture == InkTexture.GRAPHITE) Graphite.grain() else BrushTip.grain(requireNotNull(stampPreset().stamp))
    val hasGrain: Boolean get() = stampPreset().stamp!!.let { it.grain != StampGrain.NONE && it.grainDepth > 0 }
    val grainScale: Float get() = stampPreset().stamp!!.grainScale
    val grainFollowsStamp: Boolean get() = stampPreset().stamp!!.grainPlacement == GrainPlacement.STAMP
    val aspectRatio: Float get() = stampPreset().stamp!!.aspectRatio
    val stabilization: Float get() = preset.stabilization
    val speedTaper: Float get() = preset.speedTaper
    val pencilGrain: Float get() = preset.pencilGrain
    fun path(size: Float, opacity: Float): NativeStampPath = NativeStampPath(stampPreset(), size, opacity)

    companion object {
        fun fromPackage(data: String): NativeBrush? = runCatching {
            NativeBrush(dev.tipstroke.core.brush.BrushPackage.decode(data).preset)
        }.getOrNull()
        fun builtIn(id: String): NativeBrush = NativeBrush(
            BrushPreset.libraryBuiltIns.firstOrNull { it.id.value == id } ?: BrushPreset.Ink,
        )
    }
}

class NativeStampPath(preset: BrushPreset, size: Float, opacity: Float) {
    private val path = StampPath(StrokeStyle(preset, size, opacity, RgbaColor(0f, 0f, 0f), BlendBehavior.PAINT))
    fun append(x: Float, y: Float, pressure: Float, tilt: Float, time: Double): List<BrushStamp> = path.append(listOf(
        StrokeSample(0, Point(x, y), pressure, tilt, 0f, (time * 1e9).toLong(), 0, PointerKind.STYLUS)))
}
