package dev.tipstroke.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.tipstroke.core.model.RgbaColor

/** Platform persistence is injected; the palette state and controls are shared. */
open class ColorShelf(savedData: String = "", initialSlots: Int = 4, initialRecent: Boolean = false,
    private val persist: (String, Int, Boolean) -> Unit) {
    var slots by mutableIntStateOf(initialSlots.coerceIn(2, 16))
        private set
    var recent by mutableStateOf(initialRecent)
        private set
    var saved by mutableStateOf(decodeColors(savedData).take(64))
        private set
    fun changeSlots(value: Int) { slots = value.coerceIn(2, 16); saveState() }
    fun toggleSource() { recent = !recent; saveState() }
    fun save(color: RgbaColor) { if (saved.size < 64 && color !in saved) { saved = saved + color; saveState() } }
    fun remove(index: Int) { saved = saved.filterIndexed { i, _ -> i != index }; saveState() }
    private fun saveState() = persist(encodeColors(saved), slots, recent)
}
fun encodeColors(colors: List<RgbaColor>): String = colors.joinToString(";") { "${it.red},${it.green},${it.blue},${it.alpha}" }
fun decodeColors(data: String): List<RgbaColor> = data.take(16384).split(';').mapNotNull { value ->
    val channels = value.split(',').mapNotNull(String::toFloatOrNull)
    if (channels.size == 4 && channels.all { it.isFinite() && it in 0f..1f }) RgbaColor(channels[0], channels[1], channels[2], channels[3]) else null
}

@Composable
fun ColorShelfEditor(shelf: ColorShelf, selected: RgbaColor, onChoose: (RgbaColor) -> Unit) {
    Text("Quick colors · ${shelf.slots} spaces")
    Row {
        TextButton(onClick = { shelf.changeSlots(shelf.slots - 2) }, enabled = shelf.slots > 2) { Text("−") }
        TextButton(onClick = { shelf.changeSlots(shelf.slots + 2) }, enabled = shelf.slots < 16) { Text("+") }
        TextButton(onClick = shelf::toggleSource) { Text(if (shelf.recent) "Recent colors" else "Drawing colors") }
    }
    Text("Saved palette · ${shelf.saved.size}/64")
    TextButton(onClick = { shelf.save(selected) }, enabled = shelf.saved.size < 64) { Text("Save current color") }
    Text("Use the canvas eyedropper to sample paint or images, then save the sampled color here.")
    shelf.saved.chunked(4).forEachIndexed { row, colors ->
        Row {
            colors.forEachIndexed { column, color ->
                Column(Modifier.weight(1f)) {
                    Box(Modifier.fillMaxWidth().height(36.dp).background(Color(color.red, color.green, color.blue, color.alpha))
                        .clickable { onChoose(color) })
                    TextButton(onClick = { shelf.remove(row * 4 + column) }) { Text("Remove") }
                }
            }
        }
    }
}
