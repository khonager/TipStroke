package dev.tipstroke.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
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
import androidx.compose.ui.unit.dp
import dev.tipstroke.core.drawing.PixelTool
import dev.tipstroke.core.model.BrushPreset
import dev.tipstroke.ui.HoldToolPicker

internal data class DockTipGroup(val id: String, val name: String, val tips: List<BrushPreset>)

/** Fixed cells only: the library owns scrolling, never the canvas favorites dock. */
@Composable
internal fun BrushFavoritesDock(
    selected: BrushPreset, favorites: List<BrushPreset>, horizontal: Boolean,
    onLibrary: () -> Unit, onBrush: (BrushPreset) -> Unit,
    onSelect: () -> Unit, onEraser: () -> Unit, onSmudge: () -> Unit,
    pixelToolsAvailable: Boolean, onPixelTool: (PixelTool) -> Unit,
    onStudio: () -> Unit, onTune: () -> Unit, tuneDescription: String,
    brushActive: Boolean, modifier: Modifier = Modifier, toolLabel: String? = null,
    onReselect: () -> Unit = {}, onTransform: () -> Unit = {}, onFitCanvas: () -> Unit = {},
    onAdjustments: () -> Unit = {}, onColorizeMask: () -> Unit = {},
    onLassoFill: () -> Unit = {}, onColorPush: () -> Unit = {},
    assists: dev.tipstroke.core.drawing.BrushAssists = dev.tipstroke.core.drawing.BrushAssists(),
    onAssists: (dev.tipstroke.core.drawing.BrushAssists) -> Unit = {},
    perspectiveMode: dev.tipstroke.core.geometry.PerspectiveMode = dev.tipstroke.core.geometry.PerspectiveMode.OFF,
    onPerspectiveMode: (dev.tipstroke.core.geometry.PerspectiveMode) -> Unit = {}, onVanishingPoint: (Int) -> Unit = {},
    groups: List<DockTipGroup> = emptyList(), onManageGroups: () -> Unit = {},
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
            val fixedSpace = 8f + (BRUSH_DOCK_FIXED_CELLS - 1) * 2f
            val slot = minOf(48f, ((available - fixedSpace) / BRUSH_DOCK_FIXED_CELLS).coerceAtLeast(1f))
            val capacity = brushFavoriteCapacity(available, slot)
            val visibleFavorites = favorites.distinctBy { it.id }.take(capacity)
            val favoriteSelected = visibleFavorites.any { it.id == selected.id }
            val groupSelected = brushActive && !favoriteSelected && groups.any { group -> group.tips.any { it.id == selected.id } }
            val buttons: @Composable () -> Unit = {
                DockCell("Tips", brushActive && !favoriteSelected && !groupSelected, slot, !horizontal, onLibrary, icon = Icons.Outlined.GridView)
                TipGroupsPicker(groups, horizontal, slot, groupSelected, selected.displayName, onBrush, onManageGroups)
                visibleFavorites.forEach { preset ->
                    key(preset.id.value) { DockCell(preset.displayName, brushActive && preset.id == selected.id, slot, !horizontal, { onBrush(preset) }, preset = preset) }
                }
                Box {
                    val tools = buildList {
                        add(DockTool("Eraser", EraserIcon) { if (toolLabel != "Eraser") onEraser() })
                        add(DockTool("Smudge", Icons.Outlined.BlurOn, onSmudge))
                        add(DockTool("Lasso fill", Icons.Outlined.FormatColorFill, onLassoFill))
                        add(DockTool("Color push", Icons.Outlined.TouchApp, onColorPush))
                        if (pixelToolsAvailable) {
                            add(DockTool("Pixel pencil", Icons.Outlined.Edit) { onPixelTool(PixelTool.PENCIL) })
                            add(DockTool("Pixel line", Icons.Outlined.ShowChart) { onPixelTool(PixelTool.LINE) })
                            add(DockTool("Dither", Icons.Outlined.Grain) { onPixelTool(PixelTool.DITHER) })
                        }
                        add(DockTool("Colorize mask", Icons.Outlined.Palette, onColorizeMask))
                        add(DockTool("Blur & bloom", Icons.Outlined.Flare, onAdjustments))
                        add(DockTool("Brush aids", Icons.Outlined.Architecture) { aidsOpen = true })
                        add(DockTool("Adjust tip", Icons.Outlined.Tune, onStudio))
                    }
                    val active = tools.find { !brushActive && it.label == toolLabel }
                    val state = active?.label ?: "Drawing tools"
                    var reopen by remember { mutableIntStateOf(0) }
                    HoldToolPicker(
                        labels = tools.map { it.label }, horizontal = horizontal, holdEnabled = holdPicker,
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
            if (horizontal) Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) { buttons() }
            else Column(verticalArrangement = Arrangement.spacedBy(2.dp)) { buttons() }
        }
    }
}

@Composable
private fun TipGroupsPicker(
    groups: List<DockTipGroup>, horizontal: Boolean, slot: Float, selected: Boolean,
    tipName: String, onBrush: (BrushPreset) -> Unit, onManage: () -> Unit,
) {
    var groupId by remember { mutableStateOf<String?>(null) }
    var reopen by remember { mutableIntStateOf(0) }
    val group = groups.firstOrNull { it.id == groupId }
    val tips = group?.tips.orEmpty()
    val labels = if (group == null) groups.map { it.name } + "Manage tip groups"
        else tips.map { it.displayName } + "Back to tip groups"
    HoldToolPicker(
        labels = labels, horizontal = horizontal, holdEnabled = true, extent = slot.dp, reopen = reopen,
        description = "Tip groups", menuTitle = group?.name ?: "Tip groups", showLabels = group == null,
        stateLabel = if (selected) tipName else "Tip groups",
        selected = selected, onClosed = { groupId = null },
        onChoose = { index ->
            when {
                group == null && index == groups.size -> onManage()
                group == null -> { groupId = groups[index].id; reopen++ }
                index == tips.size -> reopen++
                else -> onBrush(tips[index])
            }
        },
        anchorIcon = { Icon(Icons.Outlined.Folder, null) },
        toolIcon = { index ->
            when {
                group == null -> Icon(if (index == groups.size) Icons.Outlined.Settings else Icons.Outlined.Folder, null)
                index == tips.size -> Icon(Icons.AutoMirrored.Outlined.ArrowBack, null)
                else -> TipStrokeThumbnail(tips[index], Modifier.size(42.dp))
            }
        },
    )
}

@Composable
private fun DockCell(description: String, selected: Boolean, extent: Float, beside: Boolean, onClick: () -> Unit,
    icon: ImageVector? = null, preset: BrushPreset? = null) {
    TipHint(description, beside) { showHint ->
        Surface(color = if (selected) Color(0xFFF4F4F2) else Color.Transparent, shape = MaterialTheme.shapes.small) {
            Box(Modifier.size(extent.dp).clip(MaterialTheme.shapes.small)
                .combinedClickable(role = Role.Button, onClick = onClick, onLongClick = showHint, onLongClickLabel = description)
                .semantics { contentDescription = description; this.selected = selected }, contentAlignment = Alignment.Center) {
                if (preset != null) TipStrokeThumbnail(preset, Modifier.fillMaxSize().padding(2.dp))
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
