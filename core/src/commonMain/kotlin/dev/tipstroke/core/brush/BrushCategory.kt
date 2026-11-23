package dev.tipstroke.core.brush

import dev.tipstroke.core.model.BrushPreset
import dev.tipstroke.core.model.MangaTips
import kotlinx.serialization.json.*
import kotlin.uuid.Uuid

data class BrushCategory(
    val id: String = Uuid.random().toString(),
    val name: String,
    val brushIds: List<String> = emptyList(),
    val showInDock: Boolean = true,
)

object BrushCategoriesData {
    fun defaults(custom: List<BrushPreset> = emptyList()) = listOf(
        BrushCategory("builtin-manga", "Manga", MangaTips.presets.map { it.id.value }),
        BrushCategory("builtin-ink", "Jetpack Ink", (BrushPreset.jetpackInk + custom.filter { it.ink != null })
            .map { it.id.value }.distinct().take(200)),
    )

    fun encode(groups: List<BrushCategory>): String {
        require(groups.size <= 64 && groups.distinctBy { it.id }.size == groups.size)
        return buildJsonArray {
            for (group in groups) {
                require(group.name.isNotBlank() && group.name.length <= 80 && group.brushIds.size <= 200 &&
                    group.brushIds.distinct() == group.brushIds)
                add(buildJsonObject {
                    put("id", group.id); put("name", group.name); put("showInDock", group.showInDock)
                    put("tips", buildJsonArray { group.brushIds.forEach { add(it) } })
                })
            }
        }.toString()
    }

    fun decode(data: String): List<BrushCategory> = Json.parseToJsonElement(data).jsonArray.map {
        val group = it.jsonObject
        BrushCategory(group.getValue("id").jsonPrimitive.content, group.getValue("name").jsonPrimitive.content,
            group.getValue("tips").jsonArray.map { tip -> tip.jsonPrimitive.content },
            group["showInDock"]?.jsonPrimitive?.booleanOrNull ?: true)
    }
}
