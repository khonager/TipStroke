package dev.tipstroke.app

import android.content.Context
import dev.tipstroke.core.model.RgbaColor
import kotlin.math.roundToInt

internal class ColorHistoryPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("color-history", Context.MODE_PRIVATE)

    fun load(): List<RgbaColor> = preferences.getString(KEY_COLORS, null)
        ?.split(',')
        ?.mapNotNull { encoded -> encoded.toLongOrNull(16)?.toInt()?.toRgbaColor() }
        ?.distinctBy(::colorKey)
        ?.take(MAX_COLORS)
        .orEmpty()

    fun record(color: RgbaColor): List<RgbaColor> {
        val updated = (listOf(color) + load())
            .distinctBy(::colorKey)
            .take(MAX_COLORS)
        save(updated)
        return updated
    }

    fun clear() {
        preferences.edit().remove(KEY_COLORS).apply()
    }

    private fun save(colors: List<RgbaColor>) {
        preferences.edit()
            .putString(KEY_COLORS, colors.joinToString(",") { colorKey(it).toUInt().toString(16) })
            .apply()
    }

    private fun Int.toRgbaColor() = RgbaColor(
        ((this ushr 16) and 0xFF) / 255f,
        ((this ushr 8) and 0xFF) / 255f,
        (this and 0xFF) / 255f,
        ((this ushr 24) and 0xFF) / 255f,
    )

    private fun colorKey(color: RgbaColor): Int =
        ((color.alpha * 255).roundToInt().coerceIn(0, 255) shl 24) or
            ((color.red * 255).roundToInt().coerceIn(0, 255) shl 16) or
            ((color.green * 255).roundToInt().coerceIn(0, 255) shl 8) or
            (color.blue * 255).roundToInt().coerceIn(0, 255)

    private companion object {
        const val KEY_COLORS = "recent"
        const val MAX_COLORS = 10
    }
}
