package dev.tipstroke.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import dev.tipstroke.core.model.PhotoEditTool

internal fun PhotoEditTool.transformLabel() = when (this) {
    PhotoEditTool.MOVE -> "Uniform"
    PhotoEditTool.STRETCH -> "Freeform"
    else -> name.lowercase().replaceFirstChar { it.uppercase() }
}

@Composable
internal fun TransformMeshOptions(tool: PhotoEditTool, divisions: Int, onDivisions: (Int) -> Unit) {
    if (tool == PhotoEditTool.WARP) Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        Text("Mesh")
        listOf(2, 4, 8).forEach { count ->
            FilterChip(divisions == count, { onDivisions(count) }, label = { Text("$count × $count") }, modifier = Modifier.padding(start = 6.dp))
        }
    }
}

@Composable
internal fun SelectionTransformPanel(
    initialFiltered: Boolean,
    onTool: (PhotoEditTool) -> Unit,
    onMesh: (Int) -> Unit,
    onFilter: (Boolean) -> Unit,
    onReadScale: () -> Pair<Float, Float>,
    onScale: (Float, Float) -> Unit,
    onRotation: (Float) -> Unit,
    onRadius: (Float) -> Unit,
    onAction: (String) -> Unit,
    onApply: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tool by remember { mutableStateOf(PhotoEditTool.MOVE) }
    var divisions by remember { mutableIntStateOf(4) }
    var filtered by remember { mutableStateOf(initialFiltered) }
    var exactSize by remember { mutableStateOf(false) }
    var width by remember { mutableStateOf("100") }
    var height by remember { mutableStateOf("100") }
    val validSize = !exactSize || (width.toFloatOrNull()?.let { it.isFinite() && it in 1f..800f } == true &&
        height.toFloatOrNull()?.let { it.isFinite() && it in 1f..800f } == true)
    fun previewSize() {
        val x=width.toFloatOrNull()?.takeIf { it.isFinite() && it in 1f..800f }
        val y=height.toFloatOrNull()?.takeIf { it.isFinite() && it in 1f..800f }
        if(x!=null && y!=null) onScale(x/100f,y/100f)
    }
    var rotation by remember { mutableFloatStateOf(0f) }
    var radius by remember { mutableFloatStateOf(.25f) }
    LaunchedEffect(Unit) { onFilter(initialFiltered); onTool(tool); onMesh(divisions); onRadius(radius) }
    Surface(modifier, shape = MaterialTheme.shapes.large, tonalElevation = 8.dp, shadowElevation = 8.dp) {
        Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Transform selection", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = onCancel) { Text("Cancel") }
                TextButton(onClick = onApply, enabled = validSize) { Text("Apply") }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PhotoEditTool.entries.forEach { option ->
                    FilterChip(tool == option, { tool = option; onTool(option) }, label = { Text(option.transformLabel()) })
                }
            }
            Text(when (tool) {
                PhotoEditTool.MOVE -> "Drag to move · drag a corner to resize uniformly"
                PhotoEditTool.STRETCH -> "Drag a corner to resize width and height independently"
                PhotoEditTool.DISTORT -> "Drag a corner to distort"
                PhotoEditTool.WARP -> "Drag a mesh point to reshape"
                else -> "Drag over a feature to reshape it"
            }, style = MaterialTheme.typography.bodySmall)
            TransformMeshOptions(tool, divisions) { divisions = it; onMesh(it) }
            if (tool in listOf(PhotoEditTool.PUSH, PhotoEditTool.BULGE, PhotoEditTool.PINCH)) {
                Text("Area size ${(radius*100).toInt()}%", style=MaterialTheme.typography.bodySmall)
                Slider(radius, { radius = it; onRadius(it) }, valueRange = .08f..1f)
            }
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(onClick = { onAction("flipX") }) { Text("Flip horizontal") }
                TextButton(onClick = { onAction("flipY") }) { Text("Flip vertical") }
                TextButton(onClick = { rotation = (rotation + 45f + 180f) % 360f - 180f; onRotation(rotation) }) { Text("Rotate 45°") }
                TextButton(onClick = { onAction("fit"); exactSize=false }) { Text("Fit to canvas") }
                TextButton(onClick = { rotation=0f; width="100"; height="100"; onAction("reset") }) { Text("Reset") }
            }
            TextButton(onClick = {
                exactSize=!exactSize
                if(exactSize) { val scale=onReadScale(); width=(scale.first*100).toString(); height=(scale.second*100).toString() }
            }) { Text(if(exactSize) "Hide exact size" else "Exact size") }
            if(exactSize) {
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(width, { width=it; if(tool==PhotoEditTool.MOVE) height=it; previewSize() },
                        Modifier.weight(1f),label={ Text("Width %") },singleLine=true,
                        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))
                    OutlinedTextField(height, { height=it; if(tool==PhotoEditTool.MOVE) width=it; previewSize() },
                        Modifier.weight(1f),label={ Text("Height %") },singleLine=true,
                        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))
                }
                Text("1–800%; linked in Uniform mode",style=MaterialTheme.typography.bodySmall)
                if(!validSize) Text("Enter a size from 1% to 800%.",color=MaterialTheme.colorScheme.error)
            }
            Text("Rotation ${rotation.toInt()}°", style = MaterialTheme.typography.bodySmall)
            Slider(rotation, { rotation=it; onRotation(it) }, valueRange=-180f..180f)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(filtered, { filtered = it; onFilter(it) })
                Text(if (filtered) "Bilinear" else "Nearest neighbor")
            }
        }
    }
}
