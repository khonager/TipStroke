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
            baseSizePx = 9f, opacity = .82f, hardness = .72f, spacing = .09f, stabilization = .24f,
            pressureToSize = PressureCurve(.14f, 1f, .72f), pressureToOpacity = PressureCurve(.2f, 1f, .62f),
            speedTaper = .24f)
        val Ink = BrushPreset(id = BrushId("ink-v1"), displayName = "Ink", engine = BrushEngine.INK,
            baseSizePx = 18f, opacity = 1f, hardness = 1f, spacing = .06f, stabilization = .2f,
            pressureToSize = PressureCurve(.09f, 1f, .58f), pressureToOpacity = PressureCurve(.3f, 1f, .78f),
            speedTaper = .12f)
        val Airbrush = BrushPreset(id = BrushId("airbrush-v1"), displayName = "Airbrush", engine = BrushEngine.AIRBRUSH,
            baseSizePx = 96f, opacity = .28f, hardness = .06f, spacing = .08f, stabilization = .1f,
            pressureToSize = PressureCurve(.48f, 1f, .9f), pressureToOpacity = PressureCurve(.26f, 1f, .78f))
        val builtIns = listOf(Pencil, Ink, Airbrush)
    }
}
