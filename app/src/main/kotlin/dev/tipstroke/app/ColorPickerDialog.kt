package dev.tipstroke.app

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tipstroke.core.model.RgbaColor
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
internal fun ColorPickerPanel(
    initialColor: RgbaColor,
    onDismiss: () -> Unit,
    onColorSelected: (RgbaColor) -> Unit,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val initialHsv = remember(initialColor) { initialColor.toHsv() }
    var hue by remember(initialColor) { mutableFloatStateOf(initialHsv[0]) }
    var saturation by remember(initialColor) { mutableFloatStateOf(initialHsv[1]) }
    var value by remember(initialColor) { mutableFloatStateOf(initialHsv[2]) }
    val selected = remember(hue, saturation, value) { hsvColor(hue, saturation, value) }

    Surface(
        modifier.width(if (compact) 310.dp else 360.dp),
        color = Color(0xE6202125),
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF47494F)),
        shadowElevation = 0.dp,
    ) {
        Column(Modifier.padding(if (compact) 14.dp else 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Choose color", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.size(38.dp).clip(CircleShape).background(selected.toCompose())
                        .border(2.dp, Color.White, CircleShape)
                        .semantics { contentDescription = "Selected color preview" },
                )
            }
            Spacer(Modifier.height(if (compact) 10.dp else 14.dp))
            HueSaturationWheel(hue, saturation, value, if (compact) 180.dp else 230.dp, onChange = { newHue, newSaturation ->
                hue = newHue
                saturation = newSaturation
            })
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Brightness", color = Color(0xFFE7E7E5), fontSize = 13.sp)
                Slider(value, { value = it }, modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
                Text("${(value * 100).roundToInt()}%", color = Color(0xFFED6A5A), fontSize = 12.sp)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onDismiss, Modifier.weight(1f)) { Text("Cancel") }
                Button(
                    onClick = { onColorSelected(selected) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFED6A5A)),
                ) { Text("Use color") }
            }
        }
    }
}

@Composable
private fun HueSaturationWheel(
    hue: Float,
    saturation: Float,
    value: Float,
    wheelSize: Dp,
    onChange: (Float, Float) -> Unit,
) {
    fun update(position: Offset, width: Float, height: Float) {
        val center = Offset(width / 2f, height / 2f)
        val dx = position.x - center.x
        val dy = position.y - center.y
        val radius = minOf(width, height) / 2f
        val newHue = ((Math.toDegrees(atan2(dy, dx).toDouble()).toFloat() + 360f) % 360f)
        val newSaturation = (hypot(dx, dy) / radius).coerceIn(0f, 1f)
        onChange(newHue, newSaturation)
    }
    Canvas(
        Modifier.size(wheelSize)
            .semantics { contentDescription = "Hue and saturation picker" }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    update(down.position, size.width.toFloat(), size.height.toFloat())
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        update(change.position, size.width.toFloat(), size.height.toFloat())
                        change.consume()
                    }
                }
            },
    ) {
        val radius = size.minDimension / 2f
        drawCircle(
            brush = Brush.sweepGradient(
                listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red),
            ),
            radius = radius,
        )
        drawCircle(
            brush = Brush.radialGradient(listOf(Color.White, Color.Transparent), radius = radius),
            radius = radius,
        )
        val radians = hue / 180f * PI.toFloat()
        val marker = center + Offset(cos(radians), sin(radians)) * (saturation * radius)
        drawCircle(Color.Black.copy(alpha = .55f), 12.dp.toPx(), marker)
        drawCircle(Color.White, 9.dp.toPx(), marker)
        drawCircle(hsvColor(hue, saturation, value).toCompose(), 6.dp.toPx(), marker)
    }
}

private fun RgbaColor.toHsv(): FloatArray = FloatArray(3).also { hsv ->
    AndroidColor.RGBToHSV(
        (red * 255).roundToInt(), (green * 255).roundToInt(), (blue * 255).roundToInt(), hsv,
    )
}

private fun hsvColor(hue: Float, saturation: Float, value: Float): RgbaColor {
    val argb = AndroidColor.HSVToColor(floatArrayOf(hue, saturation, value))
    return RgbaColor(
        AndroidColor.red(argb) / 255f,
        AndroidColor.green(argb) / 255f,
        AndroidColor.blue(argb) / 255f,
    )
}

private fun RgbaColor.toCompose() = Color(red, green, blue, alpha)
