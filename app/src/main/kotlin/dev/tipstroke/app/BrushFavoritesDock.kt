package dev.tipstroke.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tipstroke.core.drawing.PixelTool
import dev.tipstroke.core.model.BrushPreset

/** Fixed cells only: the library owns scrolling, never the canvas favorites dock. */
@Composable
internal fun BrushFavoritesDock(
    selected: BrushPreset, favorites: List<BrushPreset>, horizontal: Boolean,
    onCapacity: (Int) -> Unit, onLibrary: () -> Unit, onBrush: (BrushPreset) -> Unit,
    onSelect: () -> Unit, onEraser: () -> Unit, onSmudge: () -> Unit,
    pixelToolsAvailable: Boolean, onPixelTool: (PixelTool) -> Unit,
    onStudio: () -> Unit, onTune: () -> Unit, tuneDescription: String,
    brushActive: Boolean, modifier: Modifier = Modifier, toolLabel: String = "Tools",
) {
    var toolsOpen by remember { mutableStateOf(false) }
    Surface(modifier, color = Color(0xE6202125), shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, Color(0xB345474D))) {
        BoxWithConstraints(Modifier.padding(4.dp)) {
            val available = if (horizontal) maxWidth.value + 8f else maxHeight.value + 8f
            // Even extremely narrow split windows keep all three fixed actions reachable.
            val slot = minOf(48f, ((available - 12f) / 3f).coerceAtLeast(1f))
            val capacity = brushFavoriteCapacity(available, slot)
            SideEffect { onCapacity(capacity) }
            val buttons: @Composable () -> Unit = {
                DockCell("Brushes", "Brushes", brushActive && favorites.take(capacity).none { it.id == selected.id }, slot, onLibrary)
                favorites.take(capacity).forEach { preset ->
                    key(preset.id.value) { DockCell(preset.displayName, preset.displayName, brushActive && preset.id == selected.id, slot, { onBrush(preset) }) }
                }
                Box {
                    DockCell(toolLabel, "Drawing tools", !brushActive || toolsOpen, slot, { toolsOpen = true })
                    DropdownMenu(toolsOpen, { toolsOpen = false }) {
                        fun action(callback: () -> Unit) { toolsOpen = false; callback() }
                        DropdownMenuItem(text = { Text("Select") }, onClick = { action(onSelect) }, modifier = Modifier.semantics { contentDescription = "Select" })
                        DropdownMenuItem(text = { Text("Eraser") }, onClick = { action(onEraser) }, modifier = Modifier.semantics { contentDescription = "Eraser" })
                        DropdownMenuItem(text = { Text("Smudge") }, onClick = { action(onSmudge) }, modifier = Modifier.semantics { contentDescription = "Smudge" })
                        if (pixelToolsAvailable) PixelTool.entries.forEach { tool ->
                            DropdownMenuItem(text = { Text(when (tool) { PixelTool.PENCIL -> "Pixel pencil"; PixelTool.LINE -> "Pixel line"; PixelTool.DITHER -> "Dither" }) }, onClick = { action { onPixelTool(tool) } })
                        }
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Adjust brush") }, onClick = { action(onStudio) })
                    }
                }
                DockCell("Adjust", tuneDescription, false, slot, onTune)
            }
            if (horizontal) Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) { buttons() }
            else Column(verticalArrangement = Arrangement.spacedBy(2.dp)) { buttons() }
        }
    }
}

@Composable
private fun DockCell(label: String, description: String, selected: Boolean, extent: Float, onClick: () -> Unit) {
    TextButton(onClick, modifier = Modifier.size(extent.dp).semantics { contentDescription = description },
        contentPadding = PaddingValues(2.dp), shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.textButtonColors(contentColor = if (selected) Color(0xFF17181B) else Color.White,
            containerColor = if (selected) Color(0xFFF4F4F2) else Color.Transparent)) {
        Text(label, fontSize = 9.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
