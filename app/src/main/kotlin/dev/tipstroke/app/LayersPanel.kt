package dev.tipstroke.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
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
    selectedId: LayerId?,
    imageTransforming: Boolean,
    onSelect: (LayerId) -> Unit,
    onToggleVisibility: (LayerId) -> Unit,
    onOpacity: (Float) -> Unit,
    onImageScale: (Float) -> Unit,
    onFitImage: () -> Unit,
    onOriginalImageSize: () -> Unit,
    onImageTransforming: (Boolean) -> Unit,
    onAddPaint: () -> Unit,
    onImportImage: () -> Unit,
    onMoveForward: () -> Unit,
    onMoveBackward: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = layers.firstOrNull { it.id == selectedId }
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
            Spacer(Modifier.height(10.dp))
            LazyColumn(Modifier.weight(1f, fill = false).heightIn(min = 74.dp, max = 280.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                items(layers, key = { it.id.value }) { layer ->
                    LayerRow(layer, layer.id == selectedId, { onSelect(layer.id) }, { onToggleVisibility(layer.id) })
                }
            }
            selected?.let { layer ->
                HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Color(0xFF3B3D42))
                ValueHeader("Layer opacity", "${(layer.opacity * 100).roundToInt()}%")
                Slider(layer.opacity, onOpacity, valueRange = 0f..1f, modifier = Modifier.fillMaxWidth().height(48.dp))
                if (layer.kind == LayerKind.IMAGE) {
                    Spacer(Modifier.height(6.dp))
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
                    Slider(layer.imageScale ?: 1f, onImageScale, valueRange = .02f..4f, modifier = Modifier.fillMaxWidth().height(48.dp))
                    Text(
                        "Original ${layer.originalWidthPx} × ${layer.originalHeightPx} px · source preserved",
                        color = Color(0xFFAAAEB4), fontSize = 11.sp,
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onFitImage, Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Fit canvas") }
                        FilledTonalButton(onOriginalImageSize, Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("100%") }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = Color(0xFF3B3D42))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PanelButton("Back", onMoveBackward)
                    PanelButton("Front", onMoveForward)
                }
                TextButton(onClick = onDelete, enabled = layers.size > 1, colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFED6A5A))) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun LayerRow(layer: LayerSummary, selected: Boolean, onSelect: () -> Unit, onVisibility: () -> Unit) {
    val shape = RoundedCornerShape(15.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (selected) Color(0xFF343034) else Color(0xFF292A2E))
            .border(if (selected) 1.5.dp else .5.dp, if (selected) Color(0xFFED6A5A) else Color(0xFF414349), shape)
            .clickable(onClick = onSelect).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LayerThumbnail(layer.kind)
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
private fun LayerThumbnail(kind: LayerKind) {
    Canvas(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFFF2F1ED))) {
        if (kind == LayerKind.IMAGE) {
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
