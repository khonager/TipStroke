package dev.tipstroke.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.tipstroke.drawing.android.ExportFormat
import kotlin.math.roundToInt

data class ExportRequest(
    val format: ExportFormat,
    val quality: Int,
    val scale: Float,
    val transparent: Boolean,
    val fileName: String,
)

@Composable
fun ExportDrawingDialog(drawingName: String, canvasWidth: Int, canvasHeight: Int, onDismiss: () -> Unit, onExport: (ExportRequest) -> Unit) {
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        ExportDrawingSheet(drawingName, canvasWidth, canvasHeight, onDismiss, onExport)
    }
}

@Composable
internal fun ExportDrawingSheet(drawingName: String, canvasWidth: Int, canvasHeight: Int, onDismiss: () -> Unit, onExport: (ExportRequest) -> Unit) {
    var format by remember { mutableStateOf(ExportFormat.PNG) }
    var quality by remember { mutableFloatStateOf(90f) }
    var scale by remember { mutableFloatStateOf(1f) }
    var transparent by remember { mutableStateOf(false) }
    var fileName by remember(drawingName, format) { mutableStateOf("${drawingName.safeFileName()}.${format.extension}") }

    fun selectFormat(selected: ExportFormat) {
        format = selected
        fileName = "${fileName.substringBeforeLast('.', fileName)}.${selected.extension}"
        if (selected == ExportFormat.JPEG) transparent = false
    }

    Surface(
        Modifier.widthIn(min = 320.dp, max = 580.dp).fillMaxWidth(.9f).verticalScroll(rememberScrollState()),
        color = Color(0xFF202125), shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, Color(0xFF47494F)), shadowElevation = 24.dp,
    ) {
            Column(Modifier.padding(24.dp)) {
                Text("Export drawing", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                ExportLabel("Format")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    ExportFormat.entries.forEach { option -> ChoiceButton(option.name, option == format, { selectFormat(option) }, Modifier.weight(1f)) }
                }
                if (format != ExportFormat.PNG) {
                    ExportLabel("Quality", "${quality.roundToInt()}%")
                    Slider(quality, { quality = it }, valueRange = 20f..100f)
                    Text("Higher quality creates larger files.", color = Color(0xFF9EA0A6), fontSize = 11.sp)
                }
                ExportLabel("Size")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    ChoiceButton("Canvas size", scale == 1f, { scale = 1f }, Modifier.weight(1f))
                    ChoiceButton("50%", scale == .5f, { scale = .5f }, Modifier.weight(1f))
                    ChoiceButton("25%", scale == .25f, { scale = .25f }, Modifier.weight(1f))
                }
                Text("Exports at ${(canvasWidth * scale).roundToInt()} × ${(canvasHeight * scale).roundToInt()} px", color = Color(0xFF9EA0A6), fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Transparent background", color = if (format == ExportFormat.JPEG) Color(0xFF777980) else Color.White, fontSize = 14.sp)
                        if (format == ExportFormat.JPEG) Text("Not available for JPEG.", color = Color(0xFF888A90), fontSize = 11.sp)
                    }
                    Switch(transparent, { transparent = it }, enabled = format != ExportFormat.JPEG)
                }
                ExportLabel("Filename")
                OutlinedTextField(fileName, { fileName = it }, Modifier.fillMaxWidth(), singleLine = true)
                HorizontalDivider(Modifier.padding(vertical = 18.dp), color = Color(0xFF414349))
                Text("Save to device", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text("Choose a folder after tapping Export.", color = Color(0xFF9EA0A6), fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp, bottom = 18.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onDismiss, Modifier.weight(1f)) { Text("Cancel") }
                    Button(onClick = { onExport(ExportRequest(format, quality.roundToInt(), scale, transparent, fileName.ifBlank { "TipStroke.${format.extension}" })) }, Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFED6A5A))) { Text("Export") }
                }
            }
    }
}

@Composable private fun ExportLabel(label: String, value: String? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 7.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        value?.let { Text(it, color = Color(0xFFED6A5A), fontSize = 13.sp) }
    }
}

@Composable private fun ChoiceButton(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    OutlinedButton(
        onClick, modifier, shape = RoundedCornerShape(11.dp),
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) Color(0xFFED6A5A) else Color(0xFF55575D)),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = if (selected) Color(0xFF3A2929) else Color.Transparent, contentColor = Color.White),
    ) { Text(label, maxLines = 1, fontSize = 12.sp) }
}

private fun String.safeFileName() = trim().ifBlank { "TipStroke drawing" }.replace(Regex("[\\/:*?\"<>|]"), "-")
