package dev.tipstroke.app

import android.content.Context
import dev.tipstroke.core.model.BrushPreset

internal data class BrushTuning(
    val hardness: Float,
    val pressureSize: Boolean,
    val pressureOpacity: Boolean,
    val speedTaper: Boolean,
)

internal class BrushPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("brush-settings", Context.MODE_PRIVATE)

    fun load(brush: BrushPreset): BrushTuning {
        val prefix = "brush_${brush.id.value}_"
        return BrushTuning(
            preferences.getFloat(prefix + "hardness", brush.hardness).coerceIn(0f, 1f),
            preferences.getBoolean(prefix + "pressure_size", true),
            preferences.getBoolean(prefix + "pressure_opacity", true),
            preferences.getBoolean(prefix + "speed_taper", brush.speedTaper > 0f),
        )
    }

    fun save(brush: BrushPreset, tuning: BrushTuning) {
        val prefix = "brush_${brush.id.value}_"
        preferences.edit()
            .putFloat(prefix + "hardness", tuning.hardness)
            .putBoolean(prefix + "pressure_size", tuning.pressureSize)
            .putBoolean(prefix + "pressure_opacity", tuning.pressureOpacity)
            .putBoolean(prefix + "speed_taper", tuning.speedTaper)
            .apply()
    }

    fun loadEraserHardness(): Float = preferences.getFloat("eraser_hardness", .35f).coerceIn(0f, 1f)
    fun saveEraserHardness(value: Float) { preferences.edit().putFloat("eraser_hardness", value.coerceIn(0f, 1f)).apply() }
}
