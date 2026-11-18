package dev.tipstroke.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.tipstroke.core.drawing.BrushAssists

/** Reusable session controls; native hosts own all drawing and center placement. */
@Composable
fun DrawingAssistsMenu(assists: BrushAssists, onAssists: (BrushAssists) -> Unit,
    mirrorCopies: Int, onMirrorCopies: (Int) -> Unit, onMirrorCenter: () -> Unit,
    perspectiveMode: dev.tipstroke.core.geometry.PerspectiveMode = dev.tipstroke.core.geometry.PerspectiveMode.OFF,
    onPerspectiveMode: (dev.tipstroke.core.geometry.PerspectiveMode) -> Unit = {}, onVanishingPoint: (Int) -> Unit = {}) {
    DropdownMenuItem(text = { Text("Paint inside") },
        leadingIcon = { Checkbox(assists.paintInside, null) },
        onClick = { onAssists(assists.copy(paintInside = !assists.paintInside)) })
    DropdownMenuItem(text = { Text("Elastic line") },
        leadingIcon = { Checkbox(assists.elasticLine, null) },
        onClick = { onAssists(assists.copy(elasticLine = !assists.elasticLine)) })
    if (assists.elasticLine) Text("Draw, hold, then drag to bend. Lift to finish.",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.widthIn(max = 260.dp).padding(horizontal = 12.dp, vertical = 4.dp))
    listOf(1, 2, 4, 6, 8, 12).forEach { count ->
        val label = when (count) { 1 -> "Mirror wheel: Off"; 2 -> "Mirror: 2 sides"; else -> "Mirror wheel: $count copies" }
        DropdownMenuItem(text = { Text(label) }, leadingIcon = { RadioButton(mirrorCopies == count, null) },
            onClick = { onMirrorCopies(count) })
    }
    if (mirrorCopies > 1) DropdownMenuItem(text = { Text("Place mirror center…") }, onClick = { onMirrorCenter() })
    HorizontalDivider()
    dev.tipstroke.core.geometry.PerspectiveMode.entries.forEach { mode ->
        val label = when(mode) {
            dev.tipstroke.core.geometry.PerspectiveMode.OFF -> "Perspective ruler: Off"
            dev.tipstroke.core.geometry.PerspectiveMode.ONE_POINT -> "Magnetic one-point ruler"
            dev.tipstroke.core.geometry.PerspectiveMode.TWO_POINT -> "Magnetic two-point ruler"
        }
        DropdownMenuItem(text={Text(label)},leadingIcon={RadioButton(mode==perspectiveMode,null)},onClick={onPerspectiveMode(mode)})
    }
    if (perspectiveMode != dev.tipstroke.core.geometry.PerspectiveMode.OFF) {
        DropdownMenuItem(text={Text("Place vanishing point 1…")},onClick={onVanishingPoint(0)})
        if (perspectiveMode == dev.tipstroke.core.geometry.PerspectiveMode.TWO_POINT)
            DropdownMenuItem(text={Text("Place vanishing point 2…")},onClick={onVanishingPoint(1)})
        Text("Initial direction chooses a guide. The ruler takes priority over Elastic line.",style=MaterialTheme.typography.bodySmall,modifier=Modifier.widthIn(max=260.dp).padding(12.dp))
    }
    Text("Paint inside keeps strokes on existing paint.", style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.widthIn(max = 260.dp).padding(horizontal = 12.dp, vertical = 4.dp))
}
