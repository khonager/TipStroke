package dev.tipstroke.app

import android.content.Context
import dev.tipstroke.core.model.BrushPreset
import dev.tipstroke.core.brush.TipDock
import org.json.JSONArray

/** Ordered pins survive rotation; only the slots that fit the current dock are shown. */
internal class BrushShelfPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("brush-shelf", Context.MODE_PRIVATE)
    fun loadDock(): TipDock {
        val saved = preferences.getString("dock-slots", null)
        return TipDock.load(saved, when {
            preferences.contains("pins") -> read("pins")
            preferences.contains("favorites") -> load()
            else -> null
        }).also { if (saved == null) saveDock(it) }
    }
    fun saveDock(dock: TipDock) {
        preferences.edit().putString("dock-slots", dock.encode()).apply()
    }
    fun load(): List<String> = read("favorites")
    private fun read(key: String): List<String> = runCatching {
        val array = JSONArray(preferences.getString(key, null) ?: return BrushPreset.builtIns.map { it.id.value })
        (0 until array.length().coerceAtMost(200)).map { array.getString(it) }.distinct()
    }.getOrElse { BrushPreset.builtIns.map { it.id.value } }
    fun save(ids: List<String>) { preferences.edit().putString("favorites", JSONArray(ids.distinct().take(200)).toString()).apply() }
}

internal fun brushDockFixedCells(showPixelTips: Boolean) = if (showPixelTips) 4 else 3

internal fun brushFavoriteCapacity(availableDp: Float, slotDp: Float = 48f, gapDp: Float = 2f, fixedCells: Int = brushDockFixedCells(true)): Int =
    (((availableDp - 8f + gapDp) / (slotDp + gapDp)).toInt() - fixedCells).coerceAtLeast(0)
