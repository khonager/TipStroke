package dev.tipstroke.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
internal fun SelectionTransformPanel(
    initialFiltered: Boolean,
    onPreview: (Float, Float, Float, Boolean) -> Unit,
    onApply: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var width by remember { mutableStateOf("100") }
    var height by remember { mutableStateOf("100") }
    var linked by remember { mutableStateOf(true) }
    var rotation by remember { mutableFloatStateOf(0f) }
    var flipX by remember { mutableStateOf(false) }
    var flipY by remember { mutableStateOf(false) }
    var filtered by remember { mutableStateOf(initialFiltered) }
    val scaleX = width.toFloatOrNull()?.takeIf { it.isFinite() && it in 1f..800f }?.div(100f)
    val scaleY = height.toFloatOrNull()?.takeIf { it.isFinite() && it in 1f..800f }?.div(100f)
    val valid = scaleX != null && scaleY != null
    LaunchedEffect(scaleX, scaleY, rotation, flipX, flipY, filtered) {
        if (scaleX != null && scaleY != null) onPreview(
            if (flipX) -scaleX else scaleX, if (flipY) -scaleY else scaleY, rotation, filtered)
    }
    Surface(modifier, shape = MaterialTheme.shapes.large, tonalElevation = 8.dp, shadowElevation = 8.dp) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Transform selection", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = onCancel) { Text("Cancel") }
                TextButton(onClick = onApply, enabled = valid) { Text("Apply") }
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(width, { width = it; if (linked) height = it }, Modifier.weight(1f),
                        label = { Text("Width %") }, singleLine = true, isError = scaleX == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    OutlinedTextField(height, { height = it; if (linked) width = it }, Modifier.weight(1f),
                        label = { Text("Height %") }, singleLine = true, isError = scaleY == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
                if (!valid) Text("Enter a size from 1% to 800%.", color = MaterialTheme.colorScheme.error)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(linked, { linked = it; if (it) height = width })
                    Text("Keep proportions")
                    Spacer(Modifier.weight(1f))
                    Text("${rotation.roundToInt()}°")
                }
                Slider(rotation, { rotation = it.roundToInt().toFloat() }, valueRange = -180f..180f)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { rotation = if (rotation > 90f) rotation - 270f else rotation + 90f }) { Text("Rotate 90°") }
                    FilterChip(flipX, { flipX = !flipX }, label = { Text("Flip horizontal") })
                    FilterChip(flipY, { flipY = !flipY }, label = { Text("Flip vertical") })
                    TextButton(onClick = { width = "100"; height = "100"; rotation = 0f; flipX = false; flipY = false }) { Text("Reset") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(filtered, { filtered = it })
                    Text("Smooth pixels when resizing")
                }
                Text("Changes are previewed until Apply. Pixels moved outside the canvas are cropped.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
