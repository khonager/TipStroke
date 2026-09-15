package dev.tipstroke.app

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import dev.tipstroke.core.model.RgbaColor
import kotlin.math.roundToInt

@Composable
internal fun TipControls(
    size: Float,
    opacity: Float,
    color: RgbaColor,
    frequentColors: List<RgbaColor>,
    onSize: (Float) -> Unit,
    onOpacity: (Float) -> Unit,
    onColor: (RgbaColor) -> Unit,
    onOpenColorPicker: () -> Unit,
    horizontal: Boolean,
    compactVertical: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        ColorSwitcher(color, frequentColors, onColor, onOpenColorPicker)
        Spacer(Modifier.height(if (compactVertical) 6.dp else 14.dp))
        if (horizontal) {
            Row(
                Modifier.widthIn(max = 390.dp).fillMaxWidth(.62f)
                    .background(Color(0xD9202125), RoundedCornerShape(20.dp)).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                PrecisionSlider("Size", "${size.roundToInt()} px", size, 2f..180f, 1f, onSize, Modifier.weight(1f))
                PrecisionSlider("Opacity", "${(opacity * 100).roundToInt()}%", opacity, .05f..1f, .01f, onOpacity, Modifier.weight(1f))
            }
        } else if (compactVertical) {
            Column(
                Modifier.width(140.dp).background(Color(0xD9202125), RoundedCornerShape(20.dp))
                    .border(1.dp, Color(0xB345474D), RoundedCornerShape(20.dp)).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                CompactPrecisionSlider("Size", "${size.roundToInt()} px", size, 2f..180f, 1f, onSize)
                HorizontalDivider(color = Color(0xFF3A3C41))
                CompactPrecisionSlider("Opacity", "${(opacity * 100).roundToInt()}%", opacity, .05f..1f, .01f, onOpacity)
            }
        } else {
            Column(
                Modifier.width(116.dp).background(Color(0xD9202125), RoundedCornerShape(24.dp))
                    .border(1.dp, Color(0xB345474D), RoundedCornerShape(24.dp)).padding(vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                VerticalPrecisionSlider("Size", "${size.roundToInt()} px", size, 2f..180f, 1f, onSize)
                HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Color(0xFF3A3C41))
                VerticalPrecisionSlider("Opacity", "${(opacity * 100).roundToInt()}%", opacity, .05f..1f, .01f, onOpacity)
            }
        }
    }
}

@Composable
private fun CompactPrecisionSlider(label: String, valueLabel: String, value: Float, range: ClosedFloatingPointRange<Float>, step: Float, onValue: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color(0xFFE7E7E5), fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Text(valueLabel, color = Color(0xFFED6A5A), fontSize = 11.sp)
    }
    Slider(value, onValue, valueRange = range, modifier = Modifier.fillMaxWidth().height(24.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        CompactStepButton("−") { onValue((value - step).coerceIn(range)) }
        CompactStepButton("+") { onValue((value + step).coerceIn(range)) }
    }
}

@Composable
private fun CompactStepButton(label: String, onClick: () -> Unit) {
    FilledTonalButton(onClick, modifier = Modifier.size(26.dp), contentPadding = PaddingValues(0.dp), shape = RoundedCornerShape(8.dp)) {
        Text(label, fontSize = 14.sp)
    }
}

@Composable
private fun ColorSwitcher(
    color: RgbaColor,
    frequentColors: List<RgbaColor>,
    onColor: (RgbaColor) -> Unit,
    onOpenColorPicker: () -> Unit,
) {
    val defaults = listOf(
        RgbaColor(.05f, .05f, .06f), RgbaColor(.93f, .42f, .35f),
        RgbaColor(.2f, .45f, .9f), RgbaColor(.18f, .65f, .48f), RgbaColor(1f, 1f, 1f),
    )
    val choices = (frequentColors + defaults).distinctBy(::colorKey).filter { colorKey(it) != colorKey(color) }.take(4)
    Row(
        Modifier.width(116.dp).height(58.dp)
            .background(Color(0xD9202125), RoundedCornerShape(20.dp))
            .border(1.dp, Color(0xB345474D), RoundedCornerShape(20.dp)).padding(5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(46.dp).clip(CircleShape).background(color.toCompose())
                .border(2.dp, Color.White, CircleShape)
                .clickable(onClick = onOpenColorPicker)
                .semantics { contentDescription = "Open color picker" },
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            choices.chunked(2).forEachIndexed { rowIndex, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEachIndexed { columnIndex, option ->
                        val position = rowIndex * 2 + columnIndex + 1
                        Box(
                            Modifier.size(22.dp).clip(CircleShape).background(option.toCompose())
                                .border(1.dp, Color.White.copy(alpha = .82f), CircleShape)
                                .clickable { onColor(option) }
                                .semantics { contentDescription = "Used color $position" },
                        )
                    }
                }
            }
        }
    }
}

private fun colorKey(color: RgbaColor): Int =
    ((color.alpha * 255).roundToInt() shl 24) or ((color.red * 255).roundToInt() shl 16) or
        ((color.green * 255).roundToInt() shl 8) or (color.blue * 255).roundToInt()

@Composable
private fun PrecisionSlider(label: String, valueLabel: String, value: Float, range: ClosedFloatingPointRange<Float>, step: Float, onValue: (Float) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Color(0xFFE7E7E5), fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text(valueLabel, color = Color(0xFFED6A5A), fontSize = 12.sp)
        }
        Slider(value, onValue, valueRange = range, modifier = Modifier.fillMaxWidth().height(44.dp))
        StepButtons(value, range, step, onValue)
    }
}

@Composable
private fun VerticalPrecisionSlider(label: String, valueLabel: String, value: Float, range: ClosedFloatingPointRange<Float>, step: Float, onValue: (Float) -> Unit) {
    Text(label, color = Color(0xFFE7E7E5), fontSize = 12.sp, fontWeight = FontWeight.Medium)
    Text(valueLabel, color = Color(0xFFED6A5A), fontSize = 12.sp)
    VerticalDragTrack(value, range, onValue)
    StepButtons(value, range, step, onValue)
}

@Composable
private fun VerticalDragTrack(value: Float, range: ClosedFloatingPointRange<Float>, onValue: (Float) -> Unit) {
    fun valueAt(y: Float, height: Float): Float {
        val fraction = (1f - ((y - 14f) / (height - 28f))).coerceIn(0f, 1f)
        return range.start + fraction * (range.endInclusive - range.start)
    }
    Canvas(
        Modifier.width(52.dp).height(188.dp)
            .semantics { contentDescription = "Vertical adjustment" }
            .pointerInput(range) {
                detectDragGestures(
                    onDragStart = { onValue(valueAt(it.y, size.height.toFloat())) },
                    onDrag = { change, _ -> onValue(valueAt(change.position.y, size.height.toFloat())); change.consume() },
                )
            },
    ) {
        val x = size.width / 2f
        val top = 14.dp.toPx()
        val bottom = size.height - top
        val fraction = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
        val thumbY = bottom - fraction * (bottom - top)
        drawLine(Color(0xFF4A4758), Offset(x, top), Offset(x, bottom), 8.dp.toPx(), StrokeCap.Round)
        drawLine(Color(0xFFED6A5A), Offset(x, thumbY), Offset(x, bottom), 8.dp.toPx(), StrokeCap.Round)
        drawCircle(Color(0xFFF8F8F5), 10.dp.toPx(), Offset(x, thumbY))
        drawCircle(Color(0xFFED6A5A), 3.dp.toPx(), Offset(x, thumbY))
    }
}

@Composable
private fun StepButtons(value: Float, range: ClosedFloatingPointRange<Float>, step: Float, onValue: (Float) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallStepButton("−") { onValue((value - step).coerceIn(range)) }
        SmallStepButton("+") { onValue((value + step).coerceIn(range)) }
    }
}

@Composable
private fun SmallStepButton(label: String, onClick: () -> Unit) {
    FilledTonalButton(onClick, modifier = Modifier.size(38.dp), contentPadding = PaddingValues(0.dp), shape = RoundedCornerShape(11.dp)) {
        Text(label, fontSize = 18.sp)
    }
}

private fun RgbaColor.toCompose() = Color(red, green, blue, alpha)
