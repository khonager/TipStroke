package dev.tipstroke.app

import android.content.Context
import android.util.AtomicFile
import dev.tipstroke.core.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.UUID

internal data class LibraryBrush(val preset: BrushPreset, val author: String = "", val description: String = "")

/** Portable, bounded, data-only brush packages; no executable content or external assets. */
internal object BrushPackage {
    const val MAX_BYTES = 64 * 1024

    fun encode(brush: LibraryBrush): String {
        val p = brush.preset
        return JSONObject().apply {
            put("format", "tipstroke-brush"); put("version", 1)
            put("id", p.id.value); put("name", p.displayName)
            put("author", brush.author); put("description", brush.description)
            put("engine", p.engine.name); put("schemaVersion", p.schemaVersion)
            put("size", p.baseSizePx); put("opacity", p.opacity); put("hardness", p.hardness)
            put("spacing", p.spacing); put("stabilization", p.stabilization)
            put("pressureSizeStart", p.pressureToSize.start); put("pressureSizeEnd", p.pressureToSize.end)
            put("pressureSizeExponent", p.pressureToSize.exponent)
            put("pressureOpacityStart", p.pressureToOpacity.start); put("pressureOpacityEnd", p.pressureToOpacity.end)
            put("pressureOpacityExponent", p.pressureToOpacity.exponent)
            put("speedTaper", p.speedTaper); put("pointSize", p.pencilPointSize)
            put("tiltSensitivity", p.pencilTiltSensitivity); put("shadeSize", p.pencilShadeSize)
            put("shadeOpacity", p.pencilShadeOpacity); put("grain", p.pencilGrain)
            put("tiltMode", p.pencilTiltMode.name); put("shadeStart", p.pencilShadeStartRadians)
            put("shadeTransition", p.pencilShadeTransitionRadians)
        }.toString(2)
    }

    fun read(input: InputStream): LibraryBrush = input.use {
        val bytes = it.readBounded(MAX_BYTES)
        require(bytes.size <= MAX_BYTES) { "Brush file is too large." }
        decode(bytes.toString(Charsets.UTF_8))
    }

    fun decode(text: String): LibraryBrush {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Brush file is too large." }
        val o = JSONObject(text)
        require(o.getString("format") == "tipstroke-brush" && o.getDouble("version") == 1.0) { "Unsupported brush file version." }
        require(o.getDouble("schemaVersion") == BrushPreset.CURRENT_SCHEMA_VERSION.toDouble()) { "Unsupported brush schema." }
        fun string(key: String, max: Int, nonempty: Boolean = false): String = o.getString(key).also {
            require(it.length <= max && (!nonempty || it.isNotBlank())) { "Invalid $key." }
        }
        fun number(key: String, min: Float, max: Float): Float = o.getDouble(key).toFloat().also {
            require(it.isFinite() && it in min..max) { "Invalid $key." }
        }
        fun curve(prefix: String) = PressureCurve(number(prefix + "Start", .01f, 1f),
            number(prefix + "End", 1f, 1f), number(prefix + "Exponent", .25f, 2f))
        return LibraryBrush(BrushPreset(
            id = BrushId(string("id", 100, true)), displayName = string("name", 60, true),
            engine = BrushEngine.valueOf(o.getString("engine")),
            baseSizePx = number("size", 2f, 180f), opacity = number("opacity", .05f, 1f),
            hardness = number("hardness", 0f, 1f), spacing = number("spacing", .001f, 1f),
            stabilization = number("stabilization", 0f, 1f),
            pressureToSize = curve("pressureSize"), pressureToOpacity = curve("pressureOpacity"),
            speedTaper = number("speedTaper", 0f, 1f), pencilPointSize = number("pointSize", .5f, 2f),
            pencilTiltSensitivity = number("tiltSensitivity", 0f, 1f), pencilShadeSize = number("shadeSize", 0f, 4f),
            pencilShadeOpacity = number("shadeOpacity", .2f, 1f), pencilGrain = number("grain", 0f, 1f),
            pencilTiltMode = PencilTiltMode.valueOf(o.getString("tiltMode")),
            pencilShadeStartRadians = number("shadeStart", BrushPreset.MIN_PENCIL_SHADE_START_RADIANS, BrushPreset.MAX_PENCIL_SHADE_START_RADIANS),
            pencilShadeTransitionRadians = number("shadeTransition", BrushPreset.MIN_PENCIL_SHADE_TRANSITION_RADIANS, BrushPreset.MAX_PENCIL_SHADE_TRANSITION_RADIANS),
        ), string("author", 80), string("description", 1000))
    }
}

/** Access on an IO dispatcher. Atomic replacement preserves the previous library on failure. */
internal class BrushLibrary(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "brush-library.json"))

    fun load(): List<LibraryBrush> {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return emptyList()
        val bytes = file.openRead().use { it.readBounded(MAX_LIBRARY_BYTES) }
        require(bytes.size <= MAX_LIBRARY_BYTES) { "Brush library is too large." }
        val array = JSONArray(bytes.toString(Charsets.UTF_8))
        require(array.length() <= MAX_BRUSHES)
        return (0 until array.length()).map { BrushPackage.decode(array.getJSONObject(it).toString()) }.also { brushes ->
            require(brushes.map { it.preset.id }.distinct().size == brushes.size) { "Duplicate brush IDs." }
        }
    }

    fun save(brushes: List<LibraryBrush>) {
        require(brushes.size <= MAX_BRUSHES) { "Your library is full (200 brushes)." }
        require(brushes.map { it.preset.id }.distinct().size == brushes.size) { "Duplicate brush IDs." }
        val array = JSONArray()
        brushes.forEach { array.put(JSONObject(BrushPackage.encode(it))) }
        val bytes = array.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_LIBRARY_BYTES)
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }

    companion object {
        const val MAX_BRUSHES = 200
        private const val MAX_LIBRARY_BYTES = 4 * 1024 * 1024
        fun copyOf(brush: LibraryBrush): LibraryBrush = brush.copy(
            preset = brush.preset.copy(id = BrushId("custom-${UUID.randomUUID()}")),
        )
    }
}

private fun InputStream.readBounded(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    while (output.size() <= limit) {
        val count = read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
        if (count < 0) break
        if (count > 0) output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
