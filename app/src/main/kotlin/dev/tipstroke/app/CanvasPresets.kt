package dev.tipstroke.app

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.view.WindowManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.floor
import kotlin.math.sqrt

internal data class CanvasPreset(
    val id: String,
    val name: String,
    val widthPx: Int,
    val heightPx: Int,
    val category: String,
)

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
        CanvasPreset("full-hd", "Full HD", 1920, 1080, "Everyday"),
        CanvasPreset("square-2048", "Square", 2048, 2048, "Everyday"),
        CanvasPreset("uhd-4k", "4K UHD", 3840, 2160, "Everyday"),
        CanvasPreset("manga-page", "Manga page (A4, 300 dpi)", 2480, 3508, "Comics"),
        CanvasPreset("manga-panel", "Manga panel", 1200, 1800, "Comics"),
        CanvasPreset("icon-64", "Small icon", 64, 64, "Icons"),
        CanvasPreset("icon-256", "App icon", 256, 256, "Icons"),
        CanvasPreset("icon-512", "Large icon", 512, 512, "Icons"),
        CanvasPreset("pixel-16", "Pixel art 16", 16, 16, "Pixel art"),
        CanvasPreset("pixel-32", "Pixel art 32", 32, 32, "Pixel art"),
        CanvasPreset("pixel-64", "Pixel art 64", 64, 64, "Pixel art"),
        CanvasPreset("pixel-128", "Pixel art 128", 128, 128, "Pixel art"),
    )
}

internal class CanvasPresetPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("canvas-presets", Context.MODE_PRIVATE)

    fun defaultId(): String = preferences.getString("default-id", "max-square") ?: "max-square"

    fun setDefault(id: String) {
        preferences.edit().putString("default-id", id).apply()
    }

    fun saved(): List<CanvasPreset> = runCatching {
        val array = JSONArray(preferences.getString("saved", "[]"))
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val width = item.optInt("width")
            val height = item.optInt("height")
            if (width !in MIN_CANVAS_SIDE..MAX_CANVAS_SIDE || height !in MIN_CANVAS_SIDE..MAX_CANVAS_SIDE) return@mapNotNull null
            CanvasPreset(item.getString("id"), item.getString("name"), width, height, "Saved")
        }
    }.getOrDefault(emptyList())

    fun add(name: String, width: Int, height: Int): CanvasPreset {
        require(width in MIN_CANVAS_SIDE..MAX_CANVAS_SIDE && height in MIN_CANVAS_SIDE..MAX_CANVAS_SIDE)
        val preset = CanvasPreset(UUID.randomUUID().toString(), name.trim(), width, height, "Saved")
        require(preset.name.isNotEmpty())
        write(saved() + preset)
        return preset
    }

    fun remove(id: String) {
        write(saved().filterNot { it.id == id })
        if (defaultId() == id) setDefault("max-square")
    }

    private fun write(presets: List<CanvasPreset>) {
        val array = JSONArray()
        presets.forEach { preset ->
            array.put(JSONObject().put("id", preset.id).put("name", preset.name)
                .put("width", preset.widthPx).put("height", preset.heightPx))
        }
        preferences.edit().putString("saved", array.toString()).apply()
    }
}
