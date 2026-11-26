package dev.tipstroke.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.*
import androidx.compose.ui.layout.Layout
import dev.tipstroke.core.drawing.PixelTool
import dev.tipstroke.core.model.BrushPreset
import dev.tipstroke.ui.HoldToolPicker

/** Keep the same child compositions and place them along the physical rail. */
@Composable
internal fun DockCells(horizontal: Boolean, reverse: Boolean, spacing: Dp, modifier: Modifier = Modifier,
    content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { children, constraints ->
        val cells = children.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val gap = spacing.roundToPx()
        val gaps = (cells.size - 1).coerceAtLeast(0) * gap
        val width = constraints.constrainWidth(if (horizontal) cells.sumOf { it.width } + gaps else cells.maxOfOrNull { it.width } ?: 0)
        val height = constraints.constrainHeight(if (!horizontal) cells.sumOf { it.height } + gaps else cells.maxOfOrNull { it.height } ?: 0)
        layout(width, height) {
            var cursor = 0
            for (cell in if (reverse) cells.asReversed() else cells) {
                if (horizontal) cell.place(cursor, (height - cell.height) / 2)
                else cell.place((width - cell.width) / 2, cursor)
                cursor += (if (horizontal) cell.width else cell.height) + gap
            }
        }
    }
}

/** Fixed cells only: the library owns scrolling, never the canvas favorites dock. */
@Composable
internal fun BrushFavoritesDock(
    selected: BrushPreset, favorites: List<BrushPreset>, horizontal: Boolean,
    onLibrary: (Int) -> Unit, onBrush: (BrushPreset) -> Unit,
    onSelect: () -> Unit, onEraser: () -> Unit, onSmudge: () -> Unit,
    onPixelTool: (PixelTool) -> Unit,
    onStudio: () -> Unit, onTune: () -> Unit, tuneDescription: String,
    brushActive: Boolean, modifier: Modifier = Modifier, toolLabel: String? = null,
    showPixelTips: Boolean = false, reverseCells: Boolean = false,
    onReselect: () -> Unit = {}, onTransform: () -> Unit = {}, onFitCanvas: () -> Unit = {},
    onAdjustments: () -> Unit = {}, onColorizeMask: () -> Unit = {},
    onLassoFill: () -> Unit = {}, onColorPush: () -> Unit = {},
    assists: dev.tipstroke.core.drawing.BrushAssists = dev.tipstroke.core.drawing.BrushAssists(),
    onAssists: (dev.tipstroke.core.drawing.BrushAssists) -> Unit = {},
    perspectiveMode: dev.tipstroke.core.geometry.PerspectiveMode = dev.tipstroke.core.geometry.PerspectiveMode.OFF,
    onPerspectiveMode: (dev.tipstroke.core.geometry.PerspectiveMode) -> Unit = {}, onVanishingPoint: (Int) -> Unit = {},
    mirrorCopies: Int = 1, onMirrorCopies: (Int) -> Unit = {}, onMirrorCenter: () -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val holdPicker = remember(context) { DrawingUiPreferences(context).holdToolPicker }
    var aidsOpen by remember { mutableStateOf(false) }
    val aidsActive = perspectiveMode != dev.tipstroke.core.geometry.PerspectiveMode.OFF || assists.paintInside || assists.elasticLine || mirrorCopies > 1
    Surface(modifier, color = Color(0xE6202125), shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, Color(0xB345474D))) {
        BoxWithConstraints(Modifier.padding(4.dp), contentAlignment = Alignment.Center) {
            val available = if (horizontal) maxWidth.value + 8f else maxHeight.value + 8f

            val fixedCells = brushDockFixedCells(showPixelTips)
            val minimumCells = fixedCells + if (favorites.isEmpty()) 0 else 1
            val slot = minOf(48f, ((available - 8f - (minimumCells - 1) * 2f) / minimumCells).coerceAtLeast(1f))
            val capacity = brushFavoriteCapacity(available, slot, fixedCells = fixedCells).coerceAtLeast(1)
            val visibleFavorites = favorites.distinctBy { it.id }
            val buttons: @Composable () -> Unit = {
                visibleFavorites.take(capacity).forEachIndexed { index, preset ->
                    val active = brushActive && preset.id == selected.id
                    key(preset.id) {
                        DockCell(preset.displayName, active, slot, !horizontal,
                            { if (active) onLibrary(index) else onBrush(preset) }, preset = preset,
                            onHold = { onLibrary(index) })
                    }
                }
                Box {
                    val tools = buildList {
                        add(DockTool("Eraser", EraserIcon, onEraser))
                        add(DockTool("Smudge", Icons.Outlined.BlurOn, onSmudge))
                        add(DockTool("Lasso fill", Icons.Outlined.FormatColorFill, onLassoFill))
                        add(DockTool("Color push", Icons.Outlined.TouchApp, onColorPush))
                        add(DockTool("Colorize mask", Icons.Outlined.Palette, onColorizeMask))
                        add(DockTool("Blur & bloom", Icons.Outlined.Flare, onAdjustments))
                        add(DockTool("Brush aids", Icons.Outlined.Architecture) { aidsOpen = true })
                        add(DockTool("Adjust tip", Icons.Outlined.Tune, onStudio))
                    }
                    val active = tools.find { !brushActive && it.label == toolLabel }
                    val state = active?.label ?: "Drawing tools"
                    var reopen by remember { mutableIntStateOf(0) }
                    HoldToolPicker(
                        labels = tools.map { it.label }, horizontal = horizontal, holdEnabled = holdPicker || toolLabel == "Eraser",
                        extent = slot.dp, reopen = reopen, stateLabel = if (aidsActive) "$state · brush aids active" else state,
                        selected = active != null || aidsActive, onTap = onEraser,
                        onChoose = { tools[it].action() },
                        anchorIcon = { Icon(active?.icon ?: EraserIcon, null) },
                        toolIcon = { Icon(tools[it].icon, null) },
                    )
                    DropdownMenu(aidsOpen, { aidsOpen = false }, containerColor = Color(0xFF202125)) {
                        DropdownMenuItem(text = { Text("Back to drawing tools") }, onClick = { aidsOpen = false; reopen++ })
                        dev.tipstroke.ui.DrawingAssistsMenu(assists, onAssists, mirrorCopies, onMirrorCopies,
                            { aidsOpen = false; onMirrorCenter() }, perspectiveMode, onPerspectiveMode,
                            { index -> aidsOpen = false; onVanishingPoint(index) })
                    }
                }
                if (showPixelTips) {
                    val entries = PixelTool.entries
                    val active = entries.find { it.displayName == toolLabel }
                    HoldToolPicker(
                        labels = entries.map { it.displayName }, horizontal = horizontal, holdEnabled = true,
                        extent = slot.dp, reopen = 0, description = "Pixel tips", stateLabel = active?.displayName ?: "Pixel tips",
                        showLabels = true, selected = active != null,
                        onChoose = { onPixelTool(entries[it]) },
                        anchorIcon = { dev.tipstroke.ui.TipFolderFace(PixelTool.presets, slot.dp) },
                        toolIcon = { dev.tipstroke.ui.TipSwatch(entries[it].preset, Modifier.size(48.dp, 26.dp)) },
                    )
                }
                val movingTools = listOf(
                    DockTool("Select", Icons.Outlined.SelectAll, onSelect),
                    DockTool("Transform", Icons.Outlined.Transform, onTransform),
                    DockTool("Reselect previous area", Icons.Outlined.History, onReselect),
                    DockTool("Fit canvas", Icons.Outlined.FitScreen, onFitCanvas),
                )
                val activeMovingTool = movingTools.find { !brushActive && it.label == toolLabel }
                HoldToolPicker(
                    labels = movingTools.map { it.label }, horizontal = horizontal, holdEnabled = true,
                    extent = slot.dp, reopen = 0, description = "Moving tools",
                    stateLabel = activeMovingTool?.label ?: "Moving tools", selected = activeMovingTool != null,
                    onChoose = { movingTools[it].action() },
                    anchorIcon = { Icon(activeMovingTool?.icon ?: Icons.Outlined.OpenWith, null) },
                    toolIcon = { Icon(movingTools[it].icon, null) },
                )
                DockCell(tuneDescription, false, slot, !horizontal, onTune, icon = Icons.Outlined.Tune)
            }
            DockCells(horizontal, reverseCells, 2.dp, content = buttons)
        }
    }
}

@Composable
private fun DockCell(description: String, selected: Boolean, extent: Float, beside: Boolean, onClick: () -> Unit,
    icon: ImageVector? = null, preset: BrushPreset? = null, onHold: (() -> Unit)? = null) {
    TipHint(description, beside) { showHint ->
        Surface(color = if (selected) Color(0xFFF4F4F2) else Color.Transparent, shape = MaterialTheme.shapes.small) {
            Box(Modifier.size(extent.dp).clip(MaterialTheme.shapes.small)
                .combinedClickable(role = Role.Button, onClick = onClick, onLongClick = onHold ?: showHint, onLongClickLabel = if (onHold != null) "Choose tip" else description)
                .semantics { contentDescription = description; this.selected = selected }, contentAlignment = Alignment.Center) {
                if (preset != null) dev.tipstroke.ui.TipSwatch(preset, Modifier.fillMaxSize().padding(2.dp))
                else if (icon != null) Icon(icon, null, tint = if (selected) Color(0xFF17181B) else Color.White,
                    modifier = Modifier.size(minOf(24f, extent).dp))
            }
        }
    }
}

private data class DockTool(val label: String, val icon: ImageVector, val action: () -> Unit)


private val EraserIcon by lazy {
    ImageVector.Builder("Eraser", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
            strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round) {
            moveTo(3f, 14f); lineTo(14f, 3f); lineTo(21f, 10f)
            lineTo(11f, 20f); lineTo(9f, 20f); close()
            moveTo(7f, 10f); lineTo(14f, 17f)
            moveTo(10f, 20f); lineTo(21f, 20f)
        }
    }.build()
}
