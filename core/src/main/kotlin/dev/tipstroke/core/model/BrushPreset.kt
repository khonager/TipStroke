package dev.tipstroke.core.model

@JvmInline value class BrushId(val value: String)

enum class BrushEngine { PENCIL, INK, AIRBRUSH }
enum class BlendBehavior { PAINT, ERASE }

data class RgbaColor(val red: Float, val green: Float, val blue: Float, val alpha: Float = 1f) {
    init { require(listOf(red, green, blue, alpha).all { it in 0f..1f }) }
}

data class PressureCurve(val start: Float = 0.18f, val end: Float = 1f, val exponent: Float = 0.72f) {
    fun map(pressure: Float): Float = start + (end - start) * Math.pow(
        pressure.coerceIn(0f, 1f).toDouble(), exponent.toDouble()
    ).toFloat()
}

data class BrushPreset(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val id: BrushId,
    val displayName: String,
    val engine: BrushEngine,
    val baseSizePx: Float,
    val opacity: Float,
    val hardness: Float,
    val spacing: Float,
    val stabilization: Float,
    val pressureToSize: PressureCurve,
    val pressureToOpacity: PressureCurve,
    /** 0 disables speed response; 1 produces the strongest everyday-use taper. */
    val speedTaper: Float = 0f,
) {
    init { require(speedTaper in 0f..1f) }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 2
        val Pencil = BrushPreset(id = BrushId("pencil-v1"), displayName = "Pencil", engine = BrushEngine.PENCIL,
            baseSizePx = 14f, opacity = .68f, hardness = .72f, spacing = .12f, stabilization = .22f,
            pressureToSize = PressureCurve(.2f, 1f, .8f), pressureToOpacity = PressureCurve(.25f, 1f, .65f))
        val Ink = BrushPreset(id = BrushId("ink-v1"), displayName = "Ink", engine = BrushEngine.INK,
            baseSizePx = 28f, opacity = 1f, hardness = 1f, spacing = .08f, stabilization = .16f,
            pressureToSize = PressureCurve(.12f, 1f, .62f), pressureToOpacity = PressureCurve(.8f, 1f, 1f))
        val Airbrush = BrushPreset(id = BrushId("airbrush-v1"), displayName = "Airbrush", engine = BrushEngine.AIRBRUSH,
            baseSizePx = 84f, opacity = .24f, hardness = .08f, spacing = .1f, stabilization = .1f,
            pressureToSize = PressureCurve(.55f, 1f, .9f), pressureToOpacity = PressureCurve(.12f, 1f, .8f))
        val builtIns = listOf(Pencil, Ink, Airbrush)
    }
}
