package dev.tipstroke.app

import android.content.Context
import dev.tipstroke.core.model.BrushPreset
import org.json.JSONArray

/** Ordered pins survive rotation; only the slots that fit the current dock are shown. */
internal class BrushShelfPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("brush-shelf", Context.MODE_PRIVATE)
    fun load(): List<String> = runCatching {
        val array = JSONArray(preferences.getString("favorites", null) ?: return BrushPreset.builtIns.map { it.id.value })
        (0 until array.length().coerceAtMost(200)).map { array.getString(it) }.distinct()
    }.getOrElse { BrushPreset.builtIns.map { it.id.value } }
    fun save(ids: List<String>) { preferences.edit().putString("favorites", JSONArray(ids.distinct().take(200)).toString()).apply() }
}

internal fun brushFavoriteCapacity(availableDp: Float, slotDp: Float = 48f, gapDp: Float = 2f): Int =
    (((availableDp - 8f + gapDp) / (slotDp + gapDp)).toInt() - 3).coerceAtLeast(0)
