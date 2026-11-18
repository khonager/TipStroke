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
import dev.tipstroke.core.drawing.BrushSizeUnit
import dev.tipstroke.core.model.RgbaColor
import kotlin.math.roundToInt
import dev.tipstroke.ui.PrecisionSlider
import dev.tipstroke.ui.VerticalPrecisionSlider

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
    onAdjustmentStart: () -> Unit = {},
    onAdjustmentEnd: () -> Unit = {},
    horizontal: Boolean,
    compactVertical: Boolean = false,
    verticalTrackHeight: Dp = if (compactVertical) 154.dp else 205.dp,
    showColorSwitcher: Boolean = true,
    horizontalWidthFraction: Float = .62f,
    sizeRange: ClosedFloatingPointRange<Float> = 1f..180f,
    modifier: Modifier = Modifier,
    sizeUnit: BrushSizeUnit = BrushSizeUnit.CANVAS_PIXELS,
    onSizeUnit: ((BrushSizeUnit) -> Unit)? = null,
    showSize: Boolean = true,
    opacityLabel: String = "Opacity",
) {
    val unitLabel = if (sizeUnit == BrushSizeUnit.SCREEN_DP) "dp" else "px"
    val sizeLabel: @Composable () -> Unit = {
        SizeValue(size, unitLabel, sizeUnit, onSizeUnit)
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (showColorSwitcher) {
            ColorSwitcher(color, frequentColors, onColor, onOpenColorPicker)
            Spacer(Modifier.height(if (compactVertical) 6.dp else 14.dp))
        }
        if (horizontal) {
            Row(
                Modifier.widthIn(max = 390.dp).fillMaxWidth(horizontalWidthFraction)
                    .background(Color(0xD9202125), RoundedCornerShape(20.dp)).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (showSize) PrecisionSlider("Size", "${size.roundToInt()} $unitLabel", size, sizeRange, 1f, onSize, onAdjustmentStart, onAdjustmentEnd, Modifier.weight(1f), sizeLabel)
                PrecisionSlider(opacityLabel, "${(opacity * 100).roundToInt()}%", opacity, .05f..1f, .01f, onOpacity, onAdjustmentStart, onAdjustmentEnd, Modifier.weight(1f))
            }
        } else {
            Column(
                Modifier.width(if (compactVertical) 74.dp else 86.dp)
                    .background(Color(0xD9202125), RoundedCornerShape(24.dp))
                    .border(1.dp, Color(0xB345474D), RoundedCornerShape(24.dp))
                    .padding(horizontal = 7.dp, vertical = if (compactVertical) 4.dp else 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (showSize) VerticalPrecisionSlider(
                    "Size", "${size.roundToInt()} $unitLabel", size, sizeRange, 1f, onSize,
                    onAdjustmentStart, onAdjustmentEnd,
                    trackHeight = verticalTrackHeight,
                    compact = compactVertical,
                    valueContent = sizeLabel,
                )
                if (showSize) HorizontalDivider(
                    Modifier.padding(vertical = if (compactVertical) 2.dp else 8.dp),
                    color = Color(0xFF3A3C41),
                )
                VerticalPrecisionSlider(
                    opacityLabel, "${(opacity * 100).roundToInt()}%", opacity, .05f..1f, .01f, onOpacity,
                    onAdjustmentStart, onAdjustmentEnd,
                    trackHeight = verticalTrackHeight,
                    compact = compactVertical,
                )
            }
        }
    }
}

@Composable
private fun SizeValue(size: Float, unitLabel: String, unit: BrushSizeUnit, onUnit: ((BrushSizeUnit) -> Unit)?) {
    var open by remember { mutableStateOf(false) }
    Box {
        Text("${size.roundToInt()} $unitLabel${if (onUnit != null) " ▾" else ""}",
            color = Color(0xFFED6A5A), fontSize = 12.sp,
            modifier = if (onUnit == null) Modifier else Modifier
                .clickable { open = true }
                .semantics { contentDescription = "Brush size units: ${if (unit == BrushSizeUnit.SCREEN_DP) "Screen size" else "Canvas pixels"}" })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Canvas pixels (px)") }, onClick = {
                onUnit?.invoke(BrushSizeUnit.CANVAS_PIXELS); open = false
            })
            DropdownMenuItem(text = { Text("Screen size (dp)") }, onClick = {
                onUnit?.invoke(BrushSizeUnit.SCREEN_DP); open = false
            })
            Text("Screen size stays constant as you zoom.\nApplies to brushes and eraser; pixel tools and smudge use px.",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun ColorSwitcher(
    color: RgbaColor,
    frequentColors: List<RgbaColor>,
    onColor: (RgbaColor) -> Unit,
    onOpenColorPicker: () -> Unit,
    compact: Boolean = false,
    embedded: Boolean = false,
) {
    val defaults = listOf(
        RgbaColor(.05f, .05f, .06f), RgbaColor(.93f, .42f, .35f),
        RgbaColor(.2f, .45f, .9f), RgbaColor(.18f, .65f, .48f), RgbaColor(1f, 1f, 1f),
    )
    val shelf = LocalColorShelf.current
    val slots = shelf?.slots ?: 4
    val choices = (frequentColors + if (shelf?.recent == true) emptyList() else defaults).distinctBy(::colorKey).filter { colorKey(it) != colorKey(color) }.take(slots)
    Row(
        Modifier.width((if (compact) 70 else 86).dp + ((slots + 1) / 2 * if (compact) 20 else 26).dp).height(if (compact) 46.dp else 58.dp)
            .then(if (embedded) Modifier else Modifier
                .background(Color(0xD9202125), RoundedCornerShape(if (compact) 16.dp else 20.dp))
                .border(1.dp, Color(0xB345474D), RoundedCornerShape(if (compact) 16.dp else 20.dp)))
            .padding(if (compact) 4.dp else 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 6.dp),
    ) {
        Box(
            Modifier.size(if (compact) 36.dp else 46.dp).clip(CircleShape).background(color.toCompose())
                .border(2.dp, Color.White, CircleShape)
                .colorDrop(color)
                .clickable(onClick = onOpenColorPicker)
                .semantics { contentDescription = "Open color picker" },
        )
        if (shelf != null) Text(
            if (shelf.recent) "R" else "D", color = Color.White,
            modifier = Modifier.clickable { shelf.toggleSource() }.semantics {
                contentDescription = if (shelf.recent) "Recent colors; switch to drawing colors" else "Drawing colors; switch to recent colors"
            })
        Column(verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 4.dp)) {
            choices.chunked((slots + 1) / 2).forEachIndexed { rowIndex, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 4.dp)) {
                    row.forEachIndexed { columnIndex, option ->
                        val position = rowIndex * ((slots + 1) / 2) + columnIndex + 1
                        Box(
                            Modifier.size(if (compact) 17.dp else 22.dp).clip(CircleShape).background(option.toCompose())
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

private fun RgbaColor.toCompose() = Color(red, green, blue, alpha)
