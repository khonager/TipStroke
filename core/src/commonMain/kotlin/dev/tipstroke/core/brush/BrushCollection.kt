package dev.tipstroke.core.brush

import kotlinx.serialization.json.*

/** The existing Android library/pack formats, exposed without Android streams or JSON classes. */
object BrushCollection {
    const val MAX_BYTES = 32 * 1024 * 1024
    fun encode(brushes: List<LibraryBrush>): String {
        require(brushes.size <= 200 && brushes.map { it.preset.id }.distinct().size == brushes.size)
        return brushes.joinToString(prefix = "[", postfix = "]") { BrushPackage.encode(it) }
            .also { require(it.encodeToByteArray().size <= MAX_BYTES) }
    }
    fun decode(text: String): List<LibraryBrush> {
        require(text.encodeToByteArray().size <= MAX_BYTES)
        val root = Json.parseToJsonElement(text)
        val items = when (root) {
            is JsonArray -> root
            is JsonObject -> when (root["format"]?.jsonPrimitive?.content) {
                "tipstroke-brush" -> JsonArray(listOf(root))
                "tipstroke-brush-pack" -> {
                    require(root["version"]?.jsonPrimitive?.int == 1)
                    root.getValue("brushes").jsonArray
                }
                else -> error("Unsupported brush file")
            }
            else -> error("Invalid brush library")
        }
        require(items.size <= 200)
        return items.map { BrushPackage.decode(it.toString()) }.also {
            require(it.map { brush -> brush.preset.id }.distinct().size == it.size)
        }
    }
}
