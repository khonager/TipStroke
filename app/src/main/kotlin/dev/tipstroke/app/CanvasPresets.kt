package dev.tipstroke.app

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.view.WindowManager
import org.json.JSONArray
import java.util.UUID
import kotlin.math.floor
import kotlin.math.sqrt

internal typealias CanvasPreset = dev.tipstroke.core.canvas.CanvasPreset

internal const val MIN_CANVAS_SIDE = 16
internal const val MAX_CANVAS_SIDE = 8192

/** The maximum square whose fully painted tile grid uses at most half the app heap class. */
internal fun maximumOneLayerSquare(memoryClassMb: Int): Int {
    val bytesPerTile = 256L * 256L * 4L
    val tileBudget = (memoryClassMb.coerceAtLeast(1).toLong() * 1024L * 1024L / 2L) / bytesPerTile
    val tilesPerSide = floor(sqrt(tileBudget.toDouble())).toInt().coerceAtLeast(1)
    return (tilesPerSide * 256).coerceIn(MIN_CANVAS_SIDE, MAX_CANVAS_SIDE)
}

internal fun builtInCanvasPresets(context: Context): List<CanvasPreset> {
    val metrics = context.resources.displayMetrics
    val bounds = if (Build.VERSION.SDK_INT >= 30) {
        context.getSystemService(WindowManager::class.java)?.currentWindowMetrics?.bounds
    } else null
    val screenWidth = (bounds?.width() ?: metrics.widthPixels).coerceIn(MIN_CANVAS_SIDE, MAX_CANVAS_SIDE)
    val screenHeight = (bounds?.height() ?: metrics.heightPixels).coerceIn(MIN_CANVAS_SIDE, MAX_CANVAS_SIDE)
    val memoryClass = context.getSystemService(ActivityManager::class.java)?.memoryClass ?: 256
    val square = maximumOneLayerSquare(memoryClass)
    return listOf(
        CanvasPreset("screen", "Screen size", screenWidth, screenHeight, "Everyday"),
        CanvasPreset("max-square", "Max one-layer square", square, square, "Everyday"),
    ) + dev.tipstroke.core.canvas.standardCanvasPresets()
}

internal class CanvasPresetPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("canvas-presets", Context.MODE_PRIVATE)

    fun favoriteIds(): List<String> = runCatching {
        val array = JSONArray(preferences.getString("favorites", "[]"))
        (0 until array.length()).map { array.getString(it) }.distinct().take(8)
    }.getOrDefault(emptyList())

    fun setFavorites(ids: List<String>) {
        preferences.edit().putString("favorites", JSONArray(ids.distinct().take(8)).toString()).apply()
    }

    fun defaultId(): String = preferences.getString("default-id", "max-square") ?: "max-square"

    fun setDefault(id: String) {
        preferences.edit().putString("default-id", id).apply()
    }

    fun saved(): List<CanvasPreset> = dev.tipstroke.core.canvas.CanvasPresetCodec.decode(
        preferences.getString("saved", "[]").orEmpty(), MAX_CANVAS_SIDE,
    )

    fun add(name: String, width: Int, height: Int): CanvasPreset {
        require(width in MIN_CANVAS_SIDE..MAX_CANVAS_SIDE && height in MIN_CANVAS_SIDE..MAX_CANVAS_SIDE)
        val preset = CanvasPreset(UUID.randomUUID().toString(), name.trim(), width, height, "Saved")
        require(preset.name.isNotEmpty())
        write(saved() + preset)
        return preset
    }

    fun remove(id: String) {
        write(saved().filterNot { it.id == id })
        setFavorites(favoriteIds().filterNot { it == id })
        if (defaultId() == id) setDefault("max-square")
    }

    private fun write(presets: List<CanvasPreset>) {
        preferences.edit().putString("saved", dev.tipstroke.core.canvas.CanvasPresetCodec.encode(presets)).apply()
    }
}
