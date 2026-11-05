package dev.tipstroke.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.tipstroke.core.geometry.SelectionOperation
import dev.tipstroke.core.model.LayerId
import dev.tipstroke.drawing.android.SelectionTool

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SelectionControls(
    referenceName: String?, referenceLayers: List<Pair<LayerId, String>>, onReference: (LayerId?) -> Unit,
    operation: SelectionOperation, onOperation: (SelectionOperation) -> Unit,
    onInvert: () -> Unit, onCut: () -> Unit, onDelete: () -> Unit,
    hasSelection: Boolean, tool: SelectionTool, moving: Boolean, canMove: Boolean, canDuplicate: Boolean,
    onTool: (SelectionTool) -> Unit, onMove: () -> Unit, onSelectAll: () -> Unit,
    onDuplicate: () -> Unit, onClear: () -> Unit, canTransform: Boolean, onTransform: () -> Unit,
    onReselect: () -> Unit, modifier: Modifier = Modifier,
) {
    var referencesOpen by remember { mutableStateOf(false) }
    Surface(modifier, color = Color(0xED202125), shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, Color(0xFF55575D))) {
        BoxWithConstraints(Modifier.padding(4.dp)) {
            val wide = maxWidth >= 336.dp
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                FlowRow(horizontalArrangement = Arrangement.Center) {
                    Row {
                        SelectionAction("Lasso selection", Icons.Outlined.Gesture, !moving && tool == SelectionTool.LASSO,
                            onLongClick = onReselect) { onTool(SelectionTool.LASSO) }
                        SelectionAction("Rectangle selection", Icons.Outlined.CropSquare, !moving && tool == SelectionTool.RECTANGLE,
                            onLongClick = onReselect) { onTool(SelectionTool.RECTANGLE) }
                        SelectionAction("Ellipse selection", Icons.Outlined.PanoramaFishEye, !moving && tool == SelectionTool.ELLIPSE,
                            onLongClick = onReselect) { onTool(SelectionTool.ELLIPSE) }
                    }
                    Row {
                        // A single tap selects the operation; drawing immediately leaves move mode.
                        listOf(Triple(SelectionOperation.REPLACE, "New selection", Icons.Outlined.SelectAll),
                            Triple(SelectionOperation.ADD, "Add to selection", Icons.Outlined.Add),
                            Triple(SelectionOperation.SUBTRACT, "Subtract from selection", Icons.Outlined.Remove),
                            Triple(SelectionOperation.INTERSECT, "Intersect selection", Icons.Outlined.JoinInner)).forEach { (mode, label, icon) ->
                            SelectionAction(label, icon, operation == mode) { onOperation(mode); onTool(tool) }
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp), color = Color(0xFF55575D))
                FlowRow(maxItemsInEachRow = if (wide) 5 else 3,
                    horizontalArrangement = Arrangement.Center) {
                    SelectionAction("Select all", Icons.Outlined.SelectAll, onLongClick = onReselect, onClick = onSelectAll)
                    SelectionAction("Invert selection", Icons.Outlined.FlipToBack, onClick = onInvert)
                    SelectionAction("Deselect", Icons.Outlined.Deselect, enabled = hasSelection, onClick = onClear)
                    SelectionAction("Move selected pixels", Icons.Outlined.OpenWith, moving, hasSelection && canMove, onClick = onMove)
                    SelectionAction("Transform selected pixels", Icons.Outlined.Transform, enabled = hasSelection && canTransform, onClick = onTransform)
                    SelectionAction("Erase selected pixels", Icons.Outlined.DeleteOutline, enabled = hasSelection && canDuplicate, onClick = onDelete)
                    SelectionAction("Copy selection to new layer", Icons.Outlined.ContentCopy, enabled = hasSelection && canDuplicate, onClick = onDuplicate)
                    SelectionAction("Cut selection to new layer", Icons.Outlined.ContentCut, enabled = hasSelection && canDuplicate, onClick = onCut)
                    Box {
                        SelectionAction("Reference strokes", Icons.Outlined.Layers, referenceName != null, onClick = { referencesOpen = true })
                        DropdownMenu(referencesOpen, { referencesOpen = false }) {
                            DropdownMenuItem(text = { Text("Reference strokes: Off") }, onClick = { onReference(null); referencesOpen = false })
                            referenceLayers.forEach { (id, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { onReference(id); referencesOpen = false }) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectionAction(label: String, icon: ImageVector, active: Boolean = false, enabled: Boolean = true,
    extent: Int = 48, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    TipHint(label) { showHint ->
        Surface(color = if (active) Color(0xFFF4F4F2) else Color.Transparent, shape = MaterialTheme.shapes.small) {
            Box(Modifier.size(extent.dp).combinedClickable(enabled = enabled, role = Role.Button,
                onClick = onClick, onLongClick = onLongClick ?: showHint,
                onLongClickLabel = if (onLongClick != null) "Reselect previous area" else label)
                .semantics { contentDescription = label; selected = active }, contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = if (!enabled) Color(0xFF686A70) else if (active) Color(0xFF17181B) else Color.White)
            }
        }
    }
}
