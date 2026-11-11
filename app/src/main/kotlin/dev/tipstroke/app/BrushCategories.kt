package dev.tipstroke.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.util.UUID

internal data class BrushCategory(val id: String = UUID.randomUUID().toString(), val name: String, val brushIds: List<String> = emptyList())

internal class BrushCategories(context: Context) {
    private val prefs = context.getSharedPreferences("brush-categories", Context.MODE_PRIVATE)
    fun load(): List<BrushCategory> = runCatching {
        val array = JSONArray(prefs.getString("categories", "[]"))
        List(array.length()) { index -> array.getJSONObject(index).let { item ->
            val ids = item.getJSONArray("tips")
            BrushCategory(item.getString("id"), item.getString("name"), List(ids.length()) { ids.getString(it) })
        } }
    }.getOrDefault(emptyList())
    fun save(categories: List<BrushCategory>) {
        require(categories.size <= 64 && categories.map { it.id }.distinct().size == categories.size)
        require(categories.all { it.name.isNotBlank() && it.name.length <= 80 && it.brushIds.size <= 200 && it.brushIds.distinct() == it.brushIds })
        prefs.edit().putString("categories", JSONArray().apply {
            categories.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("tips", JSONArray(it.brushIds))) }
        }.toString()).apply()
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
