package dev.tipstroke.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import dev.tipstroke.core.model.RgbaColor
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
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
    onReselect: () -> Unit = {},
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
                DockCell("Tips", brushActive && favorites.take(capacity).none { it.id == selected.id }, slot, !horizontal, onLibrary, icon = Icons.Outlined.GridView)
                favorites.take(capacity).forEach { preset ->
                    key(preset.id.value) { DockCell(preset.displayName, brushActive && preset.id == selected.id, slot, !horizontal, { onBrush(preset) }, preset = preset) }
                }
                Box {
                    DockCell("Drawing tools", !brushActive || toolsOpen, slot, !horizontal, { toolsOpen = true }, icon = when (toolLabel) {
                        "Eraser" -> Icons.Outlined.AutoFixNormal
                        "Smudge" -> Icons.Outlined.BlurOn
                        "Select" -> Icons.Outlined.SelectAll
                        else -> Icons.Outlined.Build
                    }, onLongClick = onReselect, stateLabel = toolLabel)
                    DropdownMenu(toolsOpen, { toolsOpen = false }) {
                        fun action(callback: () -> Unit) { toolsOpen = false; callback() }
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .combinedClickable(role = Role.Button, onClick = { action(onSelect) },
                                onLongClick = { action(onReselect) }, onLongClickLabel = "Reselect previous area")
                            .semantics { contentDescription = "Select" }.padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.SelectAll, null)
                            Spacer(Modifier.width(12.dp))
                            Text("Select")
                        }
                        DropdownMenuItem(text = { Text("Eraser") }, onClick = { action(onEraser) }, modifier = Modifier.semantics { contentDescription = "Eraser" })
                        DropdownMenuItem(text = { Text("Smudge") }, onClick = { action(onSmudge) }, modifier = Modifier.semantics { contentDescription = "Smudge" })
                        if (pixelToolsAvailable) PixelTool.entries.forEach { tool ->
                            DropdownMenuItem(text = { Text(when (tool) { PixelTool.PENCIL -> "Pixel pencil"; PixelTool.LINE -> "Pixel line"; PixelTool.DITHER -> "Dither" }) }, onClick = { action { onPixelTool(tool) } })
                        }
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Adjust tip") }, onClick = { action(onStudio) })
                    }
                }
                DockCell(tuneDescription, false, slot, !horizontal, onTune, icon = Icons.Outlined.Tune)
            }
            if (horizontal) Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) { buttons() }
            else Column(verticalArrangement = Arrangement.spacedBy(2.dp)) { buttons() }
        }
    }
}

@Composable
private fun DockCell(description: String, selected: Boolean, extent: Float, beside: Boolean, onClick: () -> Unit,
    icon: ImageVector? = null, preset: BrushPreset? = null, onLongClick: (() -> Unit)? = null, stateLabel: String? = null) {
    TipHint(description, beside) { showHint ->
        Surface(color = if (selected) Color(0xFFF4F4F2) else Color.Transparent, shape = MaterialTheme.shapes.small) {
            Box(Modifier.size(extent.dp).clip(MaterialTheme.shapes.small)
                .combinedClickable(role = Role.Button, onClick = onClick, onLongClick = onLongClick ?: showHint, onLongClickLabel = if (onLongClick != null) "Reselect previous area" else description)
                .semantics { contentDescription = description; this.selected = selected; stateLabel?.let { stateDescription = it } }, contentAlignment = Alignment.Center) {
                if (preset != null) TipStrokeThumbnail(preset, Modifier.fillMaxSize().padding(2.dp),
                    color = if (selected) RgbaColor(.22f, .3f, .72f) else RgbaColor(.58f, .7f, 1f))
                else if (icon != null) Icon(icon, null, tint = if (selected) Color(0xFF17181B) else Color.White,
                    modifier = Modifier.size(minOf(24f, extent).dp))
            }
        }
    }
}
