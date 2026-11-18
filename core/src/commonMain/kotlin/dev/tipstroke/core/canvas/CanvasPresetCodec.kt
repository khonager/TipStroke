package dev.tipstroke.core.canvas

import kotlinx.serialization.json.*

/** Local preference payload; unrelated to drawing/project schema versions. */
object CanvasPresetCodec {
    fun encode(presets: List<CanvasPreset>): String = buildJsonArray {
        presets.forEach { preset -> add(buildJsonObject {
            put("id", preset.id); put("name", preset.name)
            put("width", preset.widthPx); put("height", preset.heightPx)
        }) }
    }.toString()

    fun decode(data: String, maximumSide: Int): List<CanvasPreset> = runCatching {
        Json.parseToJsonElement(data).jsonArray.mapNotNull { value ->
            runCatching {
                val item = value.jsonObject
                val width = item.getValue("width").jsonPrimitive.int
                val height = item.getValue("height").jsonPrimitive.int
                val id = item.getValue("id").jsonPrimitive.content
                val name = item.getValue("name").jsonPrimitive.content
                require(width in 16..maximumSide && height in 16..maximumSide && id.isNotBlank() && name.isNotBlank())
                CanvasPreset(id, name, width, height, "Saved")
            }.getOrNull()
        }.distinctBy { it.id }
    }.getOrDefault(emptyList())
}
