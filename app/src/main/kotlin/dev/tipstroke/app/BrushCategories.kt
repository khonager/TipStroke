package dev.tipstroke.app

import android.content.Context
import dev.tipstroke.core.model.BrushPreset
import dev.tipstroke.core.brush.BrushCategoriesData
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream

internal typealias BrushCategory = dev.tipstroke.core.brush.BrushCategory

internal class BrushCategories(context: Context) {
    private val prefs = context.getSharedPreferences("brush-categories", Context.MODE_PRIVATE)
    fun load(library: List<LibraryBrush> = emptyList()): List<BrushCategory> = runCatching {
        val saved = BrushCategoriesData.decode(prefs.getString("categories", "[]") ?: "[]")
        if (prefs.getBoolean("defaultGroupsAdded", false)) saved else {
            val defaults = BrushCategoriesData.defaults(library.map { it.preset })
            saved + defaults.filter { group -> saved.none { it.name.equals(group.name, true) } }.take(64 - saved.size)
        }
    }.getOrDefault(emptyList())
    fun save(categories: List<BrushCategory>) {
        prefs.edit().putBoolean("defaultGroupsAdded", true)
            .putString("categories", BrushCategoriesData.encode(categories)).apply()
    }
}

internal data class ImportedBrushPack(val name: String?, val brushes: List<LibraryBrush>)
internal object BrushPack {
    const val MAX_BYTES = 32 * 1024 * 1024
    fun encode(name: String, brushes: List<LibraryBrush>): String {
        require(name.isNotBlank() && name.length <= 80)
        require(brushes.isNotEmpty() && brushes.size <= BrushLibrary.MAX_BRUSHES)
        require(brushes.map { it.preset.id }.distinct().size == brushes.size)
        return JSONObject().put("format", "tipstroke-brush-pack").put("version", 1).put("name", name)
            .put("brushes", JSONArray().apply { brushes.forEach { put(JSONObject(BrushPackage.encode(it))) } })
            .toString().also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Pack is too large." } }
    }
    fun read(input: InputStream): ImportedBrushPack = input.use {
        val bytes = it.readBounded(MAX_BYTES)
        require(bytes.size <= MAX_BYTES) { "Pack is too large." }
        val text = bytes.toString(Charsets.UTF_8)
        val root = JSONObject(text)
        if (root.optString("format") == "tipstroke-brush") return@use ImportedBrushPack(null, listOf(BrushPackage.decode(text)))
        require(root.getString("format") == "tipstroke-brush-pack" && root.getDouble("version") == 1.0) { "Unsupported tip pack." }
        val name = root.getString("name")
        require(name.isNotBlank() && name.length <= 80)
        val array = root.getJSONArray("brushes")
        require(array.length() in 1..BrushLibrary.MAX_BRUSHES)
        val brushes = List(array.length()) { index -> BrushPackage.decode(array.getJSONObject(index).toString()) }
        require(brushes.map { brush -> brush.preset.id }.distinct().size == brushes.size) { "Duplicate tips in pack." }
        ImportedBrushPack(name, brushes)
    }
}
