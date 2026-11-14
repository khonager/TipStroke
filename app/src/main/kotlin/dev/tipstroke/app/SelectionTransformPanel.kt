package dev.tipstroke.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.Color
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
            FilterChip(divisions == count, { onDivisions(count) }, label = { Text("$count × $count") }, modifier = Modifier.padding(start = 6.dp),
                colors=FilterChipDefaults.filterChipColors(labelColor=Color.White,
                    selectedContainerColor=Color(0xFF167DDF),selectedLabelColor=Color.White))
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
    Surface(modifier, color=Color(0xF0202125), contentColor=Color.White, shape = MaterialTheme.shapes.large, tonalElevation = 8.dp, shadowElevation = 8.dp) {
        Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Transform selection", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                IconButton(onClick = onCancel) { Icon(Icons.Outlined.Close,"Cancel transform") }
                IconButton(onClick = onApply, enabled = validSize) { Icon(Icons.Outlined.Check,"Apply transform") }
            }
            TransformToolbar(tool,{ tool=it; onTool(it) },filtered,{ filtered=it;onFilter(it) },
                onAction={ action ->
                    when(action) {
                        "rotate" -> { rotation=(rotation+225f)%360f-180f;onRotation(rotation) }
                        "reset" -> { rotation=0f;width="100";height="100";onAction(action) }
                        else -> { onAction(action);exactSize=false }
                    }
                },precision=exactSize,onPrecision={
                    exactSize=!exactSize
                    if(exactSize) { val scale=onReadScale();width=(scale.first*100).toString();height=(scale.second*100).toString() }
                })
            TransformMeshOptions(tool, divisions) { divisions = it; onMesh(it) }
            if (tool in listOf(PhotoEditTool.PUSH, PhotoEditTool.BULGE, PhotoEditTool.PINCH)) {
                Text("Area size ${(radius*100).toInt()}%", style=MaterialTheme.typography.bodySmall)
                Slider(radius, { radius = it; onRadius(it) }, valueRange = .08f..1f)
            }
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
            if(exactSize) {
                Text("Rotation ${rotation.toInt()}°", style = MaterialTheme.typography.bodySmall)
                Slider(rotation, { rotation=it; onRotation(it) }, valueRange=-180f..180f)
            }
        }
    }
}
