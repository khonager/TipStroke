package dev.tipstroke.core.model

@JvmInline value class BrushId(val value: String)

enum class BrushEngine { PENCIL, INK, AIRBRUSH }
enum class BlendBehavior { PAINT, ERASE }
enum class PencilTiltMode { SHADING_SWITCH, GRADUAL }

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
    /** Pencil-only local tuning. Other engines retain these defaults without using them. */
    val pencilPointSize: Float = 1f,
    val pencilTiltSensitivity: Float = DEFAULT_PENCIL_TILT_SENSITIVITY,
    val pencilShadeSize: Float = 1f,
    val pencilShadeOpacity: Float = .62f,
    val pencilGrain: Float = 1f,
    val pencilTiltMode: PencilTiltMode = PencilTiltMode.SHADING_SWITCH,
    val pencilShadeStartRadians: Float = DEFAULT_PENCIL_SHADE_START_RADIANS,
    val pencilShadeTransitionRadians: Float = DEFAULT_PENCIL_SHADE_TRANSITION_RADIANS,
) {
    init {
        require(speedTaper in 0f..1f)
        require(pencilPointSize in .5f..2f)
        require(pencilTiltSensitivity in 0f..1f)
        require(pencilShadeSize in 0f..MAX_PENCIL_SHADE_SIZE)
        require(pencilShadeOpacity in .2f..1f)
        require(pencilGrain in 0f..1f)
        require(pencilShadeStartRadians in MIN_PENCIL_SHADE_START_RADIANS..MAX_PENCIL_SHADE_START_RADIANS)
        require(pencilShadeTransitionRadians in MIN_PENCIL_SHADE_TRANSITION_RADIANS..MAX_PENCIL_SHADE_TRANSITION_RADIANS)
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 2
        const val MIN_PENCIL_FULL_TILT_RADIANS = .08f
        const val MAX_PENCIL_FULL_TILT_RADIANS = 1.2f
        const val DEFAULT_PENCIL_FULL_TILT_RADIANS = .55f
        const val PENCIL_TILT_DEAD_ZONE_RADIANS = .02f
        const val MIN_PENCIL_SHADE_START_RADIANS = .08f
        const val MAX_PENCIL_SHADE_START_RADIANS = 1.1f
        const val MIN_PENCIL_SHADE_TRANSITION_RADIANS = .035f
        const val MAX_PENCIL_SHADE_TRANSITION_RADIANS = 1.12f
        const val DEFAULT_PENCIL_SHADE_START_RADIANS = .7853982f
        const val DEFAULT_PENCIL_SHADE_TRANSITION_RADIANS = .10471976f
        const val MAX_PENCIL_SHADE_SIZE = 4f
        const val DEFAULT_PENCIL_TILT_SENSITIVITY =
            (MAX_PENCIL_FULL_TILT_RADIANS - DEFAULT_PENCIL_FULL_TILT_RADIANS) /
                (MAX_PENCIL_FULL_TILT_RADIANS - MIN_PENCIL_FULL_TILT_RADIANS)
        val Pencil = BrushPreset(id = BrushId("pencil-v1"), displayName = "Pencil", engine = BrushEngine.PENCIL,
            baseSizePx = 9f, opacity = .82f, hardness = .72f, spacing = .09f, stabilization = .24f,
            pressureToSize = PressureCurve(.14f, 1f, .72f), pressureToOpacity = PressureCurve(.1f, 1f, .72f),
            speedTaper = .24f)
        val Ink = BrushPreset(id = BrushId("ink-v1"), displayName = "Ink", engine = BrushEngine.INK,
            baseSizePx = 18f, opacity = 1f, hardness = 1f, spacing = .06f, stabilization = .2f,
            pressureToSize = PressureCurve(.09f, 1f, .58f), pressureToOpacity = PressureCurve(.12f, 1f, .78f),
            speedTaper = .12f)
        val Airbrush = BrushPreset(id = BrushId("airbrush-v1"), displayName = "Airbrush", engine = BrushEngine.AIRBRUSH,
            baseSizePx = 96f, opacity = .28f, hardness = .06f, spacing = .08f, stabilization = .1f,
            pressureToSize = PressureCurve(.48f, 1f, .9f), pressureToOpacity = PressureCurve(.26f, 1f, .78f))
        val builtIns = listOf(Pencil, Ink, Airbrush)
    }
}

fun BrushPreset.pencilFullTiltRadians(): Float =
    BrushPreset.MAX_PENCIL_FULL_TILT_RADIANS -
        (BrushPreset.MAX_PENCIL_FULL_TILT_RADIANS - BrushPreset.MIN_PENCIL_FULL_TILT_RADIANS) * pencilTiltSensitivity

fun pencilTiltSensitivityForFullAngle(tiltRadians: Float): Float =
    ((BrushPreset.MAX_PENCIL_FULL_TILT_RADIANS - tiltRadians) /
        (BrushPreset.MAX_PENCIL_FULL_TILT_RADIANS - BrushPreset.MIN_PENCIL_FULL_TILT_RADIANS))
        .coerceIn(0f, 1f)

fun BrushPreset.pencilShadeEndRadians(): Float =
    (pencilShadeStartRadians + pencilShadeTransitionRadians)
        .coerceAtMost(BrushPreset.MAX_PENCIL_FULL_TILT_RADIANS)

fun BrushPreset.pencilTiltResponse(tiltRadians: Float): Float = when (pencilTiltMode) {
    PencilTiltMode.GRADUAL -> {
        val normalized = (
            (tiltRadians - BrushPreset.PENCIL_TILT_DEAD_ZONE_RADIANS) /
                (pencilFullTiltRadians() - BrushPreset.PENCIL_TILT_DEAD_ZONE_RADIANS)
            ).coerceIn(0f, 1f)
        1f - (1f - normalized) * (1f - normalized) * (1f - normalized)
    }
    PencilTiltMode.SHADING_SWITCH -> {
        val effectiveTransition = pencilShadeEndRadians() - pencilShadeStartRadians
        val normalized = (
            (tiltRadians - pencilShadeStartRadians) / effectiveTransition
            ).coerceIn(0f, 1f)
        normalized * normalized * (3f - 2f * normalized)
    }
}
