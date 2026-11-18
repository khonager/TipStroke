package dev.tipstroke.app

import android.content.Context
import android.util.AtomicFile
import dev.tipstroke.core.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.UUID

internal typealias LibraryBrush = dev.tipstroke.core.brush.LibraryBrush

/** Android stream adapter; schema and validation live in commonMain. */
internal object BrushPackage {
    const val MAX_BYTES = dev.tipstroke.core.brush.BrushPackage.MAX_BYTES
    fun encode(brush: LibraryBrush) = dev.tipstroke.core.brush.BrushPackage.encode(brush)
    fun decode(text: String) = dev.tipstroke.core.brush.BrushPackage.decode(text)
    fun read(input: InputStream): LibraryBrush = input.use {
        val bytes = it.readBounded(MAX_BYTES)
        require(bytes.size <= MAX_BYTES) { "Tip file is too large." }
        decode(bytes.toString(Charsets.UTF_8))
    }
}

/** Access on an IO dispatcher. Atomic replacement preserves the previous library on failure. */
internal class BrushLibrary(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "brush-library.json"))

    fun load(): List<LibraryBrush> {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return emptyList()
        val bytes = file.openRead().use { it.readBounded(MAX_LIBRARY_BYTES) }
        require(bytes.size <= MAX_LIBRARY_BYTES) { "Tip library is too large." }
        val array = JSONArray(bytes.toString(Charsets.UTF_8))
        require(array.length() <= MAX_BRUSHES)
        return (0 until array.length()).map { BrushPackage.decode(array.getJSONObject(it).toString()) }.also { brushes ->
            require(brushes.map { it.preset.id }.distinct().size == brushes.size) { "Duplicate tip IDs." }
        }
    }

    fun save(brushes: List<LibraryBrush>) {
        require(brushes.size <= MAX_BRUSHES) { "Your library is full (200 tips)." }
        require(brushes.map { it.preset.id }.distinct().size == brushes.size) { "Duplicate tip IDs." }
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
        private const val MAX_LIBRARY_BYTES = 32 * 1024 * 1024
        fun copyOf(brush: LibraryBrush): LibraryBrush = brush.copy(
            preset = brush.preset.copy(id = BrushId("custom-${UUID.randomUUID()}")),
        )
    }
}

internal fun InputStream.readBounded(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    while (output.size() <= limit) {
        val count = read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
        if (count < 0) break
        if (count > 0) output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
