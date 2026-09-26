package dev.tipstroke.app

import android.content.Context
import dev.tipstroke.core.model.BrushPreset
import dev.tipstroke.core.model.PencilTiltMode

internal data class BrushTuning(
    val sizePx: Float,
    val opacity: Float,
    val hardness: Float,
    val pressureSize: Boolean,
    val pressureOpacity: Boolean,
    val speedTaper: Boolean,
    val pencilPointSize: Float = 1f,
    val pencilTiltSensitivity: Float = BrushPreset.DEFAULT_PENCIL_TILT_SENSITIVITY,
    val pencilShadeSize: Float = 1f,
    val pencilShadeOpacity: Float = .62f,
    val pencilGrain: Float = 1f,
    val pencilTiltMode: PencilTiltMode = PencilTiltMode.SHADING_SWITCH,
    val pencilShadeStartRadians: Float = BrushPreset.DEFAULT_PENCIL_SHADE_START_RADIANS,
    val pencilShadeTransitionRadians: Float = BrushPreset.DEFAULT_PENCIL_SHADE_TRANSITION_RADIANS,
)

internal class BrushPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("brush-settings", Context.MODE_PRIVATE)

    fun load(brush: BrushPreset): BrushTuning {
        val prefix = "brush_${brush.id.value}_"
        return load(prefix, BrushTuning(
            brush.baseSizePx, brush.opacity, brush.hardness, true, true, brush.speedTaper > 0f,
            brush.pencilPointSize,
            brush.pencilTiltSensitivity,
            brush.pencilShadeSize,
            brush.pencilShadeOpacity,
            brush.pencilGrain,
            brush.pencilTiltMode,
            brush.pencilShadeStartRadians,
            brush.pencilShadeTransitionRadians,
        ))
    }

    fun save(brush: BrushPreset, tuning: BrushTuning) {
        val prefix = "brush_${brush.id.value}_"
        preferences.edit()
            .putFloat(prefix + "size", tuning.sizePx)
            .putFloat(prefix + "opacity", tuning.opacity)
            .putFloat(prefix + "hardness", tuning.hardness)
            .putBoolean(prefix + "pressure_size", tuning.pressureSize)
            .putBoolean(prefix + "pressure_opacity", tuning.pressureOpacity)
            .putBoolean(prefix + "speed_taper", tuning.speedTaper)
            .putFloat(prefix + "pencil_point_size", tuning.pencilPointSize)
            .putFloat(prefix + "pencil_tilt_sensitivity", tuning.pencilTiltSensitivity)
            .putFloat(prefix + "pencil_shade_size", tuning.pencilShadeSize)
            .putFloat(prefix + "pencil_shade_opacity", tuning.pencilShadeOpacity)
            .putFloat(prefix + "pencil_grain", tuning.pencilGrain)
            .putString(prefix + "pencil_tilt_mode", tuning.pencilTiltMode.name)
            .putFloat(prefix + "pencil_shade_start", tuning.pencilShadeStartRadians)
            .putFloat(prefix + "pencil_shade_transition", tuning.pencilShadeTransitionRadians)
            .apply()
    }

    fun loadEraser(): BrushTuning = load("eraser_", BrushTuning(32f, 1f, .35f, true, true, false))
    fun saveEraser(tuning: BrushTuning) {
        preferences.edit()
            .putFloat("eraser_size", tuning.sizePx)
            .putFloat("eraser_opacity", tuning.opacity)
            .putFloat("eraser_hardness", tuning.hardness)
            .putBoolean("eraser_pressure_size", tuning.pressureSize)
            .putBoolean("eraser_pressure_opacity", tuning.pressureOpacity)
            .putBoolean("eraser_speed_taper", tuning.speedTaper)
            .apply()
    }

    private fun load(prefix: String, fallback: BrushTuning) = BrushTuning(
        preferences.getFloat(prefix + "size", fallback.sizePx).coerceIn(2f, 180f),
        preferences.getFloat(prefix + "opacity", fallback.opacity).coerceIn(.05f, 1f),
        preferences.getFloat(prefix + "hardness", fallback.hardness).coerceIn(0f, 1f),
        preferences.getBoolean(prefix + "pressure_size", fallback.pressureSize),
        preferences.getBoolean(prefix + "pressure_opacity", fallback.pressureOpacity),
        preferences.getBoolean(prefix + "speed_taper", fallback.speedTaper),
        preferences.getFloat(prefix + "pencil_point_size", fallback.pencilPointSize).coerceIn(.5f, 2f),
        preferences.getFloat(prefix + "pencil_tilt_sensitivity", fallback.pencilTiltSensitivity).coerceIn(0f, 1f),
        preferences.getFloat(prefix + "pencil_shade_size", fallback.pencilShadeSize)
            .coerceIn(0f, BrushPreset.MAX_PENCIL_SHADE_SIZE),
        preferences.getFloat(prefix + "pencil_shade_opacity", fallback.pencilShadeOpacity).coerceIn(.2f, 1f),
        preferences.getFloat(prefix + "pencil_grain", fallback.pencilGrain).coerceIn(0f, 1f),
        runCatching {
            PencilTiltMode.valueOf(
                preferences.getString(prefix + "pencil_tilt_mode", fallback.pencilTiltMode.name)
                    ?: fallback.pencilTiltMode.name,
            )
        }.getOrDefault(fallback.pencilTiltMode),
        preferences.getFloat(prefix + "pencil_shade_start", fallback.pencilShadeStartRadians)
            .coerceIn(BrushPreset.MIN_PENCIL_SHADE_START_RADIANS, BrushPreset.MAX_PENCIL_SHADE_START_RADIANS),
        preferences.getFloat(prefix + "pencil_shade_transition", fallback.pencilShadeTransitionRadians)
            .coerceIn(BrushPreset.MIN_PENCIL_SHADE_TRANSITION_RADIANS, BrushPreset.MAX_PENCIL_SHADE_TRANSITION_RADIANS),
    )
}
