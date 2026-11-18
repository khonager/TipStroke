package dev.tipstroke.core.editor

import dev.tipstroke.core.model.*

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

    companion object {
        fun builtIn(id: String): NativeBrush = NativeBrush(
            BrushPreset.builtIns.firstOrNull { it.id.value == id } ?: BrushPreset.Ink,
        )
    }
}
