package dev.tipstroke.core.brush

import dev.tipstroke.core.model.BrushPreset
import kotlinx.serialization.json.*

/** Slot assignments survive window changes. Only the host decides how many swatches fit. */
class TipDock private constructor(private val slots: List<Slot>) {
    private data class Slot(val tip: String?, val previous: List<String> = emptyList())

    private fun resolved(available: Set<String>): List<String?> {
        val current = slots.map { it.tip?.takeIf(available::contains) }
        val used = current.filterNotNull().toMutableSet()
        return slots.mapIndexed { index, slot ->
            if (slot.tip != null) current[index] else (slot.previous.asSequence() + recommended.asSequence())
                .firstOrNull { it in available && used.add(it) }
        }
    }

    fun ordered(available: Set<String>) = resolved(available).filterNotNull()

    fun replace(slot: Int, id: String, available: Set<String>): TipDock {
        val equipped = resolved(available)
        val target = equipped.indices.filter { equipped[it] != null }.getOrNull(slot) ?: return this
        if (id !in available || equipped[target] == id) return this
        return TipDock(slots.mapIndexed { index, old ->
            when {
                index == target -> Slot(id, (listOfNotNull(equipped[index], old.tip) + old.previous)
                    .distinct().filterNot { it == id }.take(20))
                equipped[index] == id -> old.copy(tip = old.tip?.takeUnless { it == id },
                    previous = old.previous.filterNot { it == id })
                else -> old
            }
        })
    }

    fun reorder(ids: List<String>, available: Set<String>): TipDock {
        val equipped = resolved(available)
        if (ids.size != equipped.count { it != null } || ids.toSet() != equipped.filterNotNull().toSet()) return this
        val byId = equipped.zip(slots).toMap()
        val reordered = ids.map { id -> Slot(id, byId[id]?.previous.orEmpty()) }.iterator()
        return TipDock(slots.mapIndexed { index, slot -> if (equipped[index] == null) slot else reordered.next() })
    }

    fun without(ids: Set<String>) = TipDock(slots.map { Slot(it.tip?.takeUnless(ids::contains), it.previous - ids) })

    fun encode() = buildJsonArray {
        slots.forEach { slot ->
            add(buildJsonArray { add(slot.tip); slot.previous.forEach { add(it) } })
        }
    }.toString()

    companion object {
        private val recommended by lazy {
            (listOf(BrushPreset.Ink, BrushPreset.Pencil, BrushPreset.Airbrush, BrushPreset.HardAirbrush,
                BrushPreset.Calligraphy) + BrushPreset.libraryBuiltIns).map { it.id.value }.distinct()
        }

        fun load(data: String?, legacy: List<String>?): TipDock {
            if (data != null) runCatching {
                return TipDock(Json.parseToJsonElement(data).jsonArray.take(200).map {
                    val slot = it.jsonArray
                    Slot(slot.first().jsonPrimitive.contentOrNull, slot.drop(1).take(20).map { tip -> tip.jsonPrimitive.content })
                })
            }
            return TipDock((legacy.orEmpty() + recommended).distinct().take(200).map { Slot(it) })
        }
    }
}
