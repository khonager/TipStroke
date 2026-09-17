package dev.tipstroke.app

import android.graphics.Bitmap
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import dev.tipstroke.core.model.*
import kotlin.math.roundToInt

@Composable
internal fun LayersPanel(
    layers: List<LayerSummary>,
    previews: Map<LayerId, Bitmap>,
    selectedId: LayerId?,
    selectedIds: Set<LayerId>,
    imageTransforming: Boolean,
    onSelect: (LayerId) -> Unit,
    onToggleSelection: (LayerId) -> Unit,
    onToggleVisibility: (LayerId) -> Unit,
    onOpacity: (Float) -> Unit,
    onRename: (String) -> Unit,
    onImageScale: (Float) -> Unit,
    onFitImage: () -> Unit,
    onOriginalImageSize: () -> Unit,
    onImageTransforming: (Boolean) -> Unit,
    onAddPaint: () -> Unit,
    onImportImage: () -> Unit,
    onMoveForward: () -> Unit,
    onMoveBackward: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var renameLayer by remember { mutableStateOf<LayerSummary?>(null) }
    var renameText by remember { mutableStateOf("") }
    Surface(
        modifier.width(324.dp).fillMaxHeight(.9f).semantics { contentDescription = "Layers panel" },
        color = Color(0xE6202125),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, Color(0xFF47494F)),
        shadowElevation = 18.dp,
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Layers", color = Color(0xFFF5F5F2), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onAddPaint, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("+ Paint") }
                TextButton(onClick = onImportImage, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("+ Image") }
            }
            Text("Front to back", color = Color(0xFF9B9DA3), fontSize = 11.sp)
            Text("Swipe a layer right to add it to the selection", color = Color(0xFF777A81), fontSize = 10.sp)
            Spacer(Modifier.height(10.dp))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                items(layers, key = { it.id.value }) { layer ->
                    Column {
                        LayerRow(
                            layer = layer,
                            preview = previews[layer.id],
                            primary = layer.id == selectedId,
                            selected = layer.id in selectedIds,
                            onSelect = { onSelect(layer.id) },
                            onToggleSelection = { onToggleSelection(layer.id) },
                            onVisibility = { onToggleVisibility(layer.id) },
                        )
                        if (layer.id == selectedId) {
                            LayerControls(
                                layer = layer,
                                imageTransforming = imageTransforming,
                                onRename = {
                                    renameLayer = layer
                                    renameText = layer.name
                                },
                                onOpacity = onOpacity,
                                onImageScale = onImageScale,
                                onFitImage = onFitImage,
                                onOriginalImageSize = onOriginalImageSize,
                                onImageTransforming = onImageTransforming,
                            )
                        }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = Color(0xFF3B3D42))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PanelButton("Back", onMoveBackward)
                    PanelButton("Front", onMoveForward)
                    PanelButton("Duplicate", onDuplicate)
                }
                TextButton(onClick = onDelete, enabled = layers.size > 1, colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFED6A5A))) { Text("Delete") }
            }
        }
    }
    renameLayer?.let {
        AlertDialog(
            onDismissRequest = { renameLayer = null },
            title = { Text("Rename layer") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it.take(80) },
                    label = { Text("Layer name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = renameText.isNotBlank(),
                    onClick = { onRename(renameText); renameLayer = null },
                ) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renameLayer = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun LayerControls(
    layer: LayerSummary,
    imageTransforming: Boolean,
    onRename: () -> Unit,
    onOpacity: (Float) -> Unit,
    onImageScale: (Float) -> Unit,
    onFitImage: () -> Unit,
    onOriginalImageSize: () -> Unit,
    onImageTransforming: (Boolean) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 4.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(layer.name, color = Color(0xFFE7E7E5), fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = onRename, contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.height(32.dp)) { Text("Rename", fontSize = 11.sp) }
        }
        ValueHeader("Layer opacity", "${(layer.opacity * 100).roundToInt()}%")
        Slider(layer.opacity, onOpacity, valueRange = 0f..1f, modifier = Modifier.fillMaxWidth().height(40.dp))
        if (layer.kind == LayerKind.IMAGE) {
            FilledTonalButton(
                onClick = { onImageTransforming(!imageTransforming) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = if (imageTransforming) Color(0xFFED6A5A) else Color(0xFF34363B),
                    contentColor = Color.White,
                ),
            ) { Text(if (imageTransforming) "Finish transforming" else "Move & resize on canvas") }
            Text(
                "Drag to move · pinch to resize · twist to rotate",
                color = Color(0xFFAAAEB4), fontSize = 11.sp,
                modifier = Modifier.padding(top = 5.dp, bottom = 8.dp),
            )
            ValueHeader("Image scale", "${((layer.imageScale ?: 1f) * 100).roundToInt()}%")
            Slider(layer.imageScale ?: 1f, onImageScale, valueRange = .02f..4f, modifier = Modifier.fillMaxWidth().height(40.dp))
            Text(
                "Original ${layer.originalWidthPx} × ${layer.originalHeightPx} px · source preserved",
                color = Color(0xFFAAAEB4), fontSize = 11.sp,
            )
            Text(
                "Choose Eraser to hide image pixels non-destructively. A selection limits where it erases.",
                color = Color(0xFFAAAEB4), fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp),
            )
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onFitImage, Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Fit canvas") }
                FilledTonalButton(onOriginalImageSize, Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("100%") }
            }
        }
    }
}

@Composable
private fun LayerRow(
    layer: LayerSummary,
    preview: Bitmap?,
    primary: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
    onToggleSelection: () -> Unit,
    onVisibility: () -> Unit,
) {
    val shape = RoundedCornerShape(15.dp)
    var dragDistance by remember(layer.id) { mutableFloatStateOf(0f) }
    val swipeThreshold = with(LocalDensity.current) { 34.dp.toPx() }
    Row(
        Modifier.fillMaxWidth().offset { IntOffset(dragDistance.coerceIn(0f, swipeThreshold).roundToInt(), 0) }.clip(shape)
            .background(if (selected) Color(0xFF343034) else Color(0xFF292A2E))
            .border(if (selected) 1.5.dp else .5.dp, if (primary) Color(0xFFED6A5A) else if (selected) Color(0xFF75A7FF) else Color(0xFF414349), shape)
            .pointerInput(layer.id, selected) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, amount ->
                        if (amount > 0f || dragDistance > 0f) {
                            change.consume()
                            dragDistance = (dragDistance + amount).coerceAtLeast(0f)
                        }
                    },
                    onDragEnd = {
                        if (dragDistance >= swipeThreshold) onToggleSelection()
                        dragDistance = 0f
                    },
                    onDragCancel = { dragDistance = 0f },
                )
            }
            .clickable(onClick = onSelect).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LayerThumbnail(layer.kind, preview)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(layer.name, color = Color(0xFFF1F1EF), fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (layer.kind == LayerKind.IMAGE) "Image · original linked" else "Paint layer",
                color = Color(0xFF9EA0A6), fontSize = 10.sp,
            )
        }
        Text("${(layer.opacity * 100).roundToInt()}%", color = Color(0xFFB9BBC0), fontSize = 10.sp)
        IconButton(onVisibility, Modifier.size(40.dp).semantics { contentDescription = if (layer.visible) "Hide ${layer.name}" else "Show ${layer.name}" }) {
            EyeIcon(layer.visible)
        }
    }
}

@Composable
private fun LayerThumbnail(kind: LayerKind, preview: Bitmap?) {
    Canvas(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFFF2F1ED))) {
        if (preview != null) {
            drawImage(preview.asImageBitmap(), dstSize = IntSize(size.width.toInt(), size.height.toInt()))
        } else if (kind == LayerKind.IMAGE) {
            drawRect(Color(0xFFB7D4DD))
            drawCircle(Color(0xFFFFD786), size.minDimension * .1f, Offset(size.width * .72f, size.height * .27f))
            val mountains = Path().apply {
                moveTo(0f, size.height)
                lineTo(size.width * .38f, size.height * .42f)
                lineTo(size.width * .58f, size.height * .7f)
                lineTo(size.width * .76f, size.height * .5f)
                lineTo(size.width, size.height * .76f)
                lineTo(size.width, size.height)
                close()
            }
            drawPath(mountains, Color(0xFF496D66))
        } else {
            val path = Path().apply {
                moveTo(size.width * .12f, size.height * .68f)
                cubicTo(size.width * .3f, size.height * .1f, size.width * .52f, size.height * .9f, size.width * .88f, size.height * .3f)
            }
            drawPath(path, Color(0xFF202125), style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

@Composable
private fun EyeIcon(visible: Boolean) {
    Canvas(Modifier.size(21.dp)) {
        val color = if (visible) Color.White else Color(0xFF777A81)
        val path = Path().apply {
            moveTo(1f, size.height / 2)
            quadraticTo(size.width / 2, 2f, size.width - 1f, size.height / 2)
            quadraticTo(size.width / 2, size.height - 2f, 1f, size.height / 2)
        }
        drawPath(path, color, style = Stroke(1.6.dp.toPx()))
        if (visible) drawCircle(color, 2.8.dp.toPx()) else drawLine(color, Offset(2f, 2f), Offset(size.width - 2f, size.height - 2f), 1.6.dp.toPx())
    }
}

@Composable
private fun ValueHeader(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color(0xFFE7E7E5), fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Text(value, color = Color(0xFFED6A5A), fontSize = 12.sp)
    }
}

@Composable
private fun PanelButton(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick, contentPadding = PaddingValues(horizontal = 11.dp), modifier = Modifier.height(40.dp)) { Text(label, fontSize = 12.sp) }
}
