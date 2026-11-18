package dev.tipstroke.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.tipstroke.core.effects.*
import kotlin.math.roundToInt

@Composable
fun RasterEffectControls(value: RasterEffectSettings, enabled: Boolean, onChange: (RasterEffectSettings) -> Unit) {
    var typesOpen by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick={typesOpen=true},enabled=enabled) { Text(value.effect.name.lowercase().replaceFirstChar { it.uppercase() }+" ▾") }
        DropdownMenu(typesOpen,{typesOpen=false}) {
            RasterEffect.entries.forEach { type -> DropdownMenuItem(text={Text(type.name.lowercase().replaceFirstChar { it.uppercase() })},onClick={typesOpen=false;onChange(value.copy(effect=type))}) }
        }
    }
    fun label(text: String, number: Float) = "$text: ${number.roundToInt()}"
    Text("Radius: ${value.radius} px")
    Slider(value.radius.toFloat(),{onChange(value.copy(radius=it.roundToInt()))},enabled=enabled,valueRange=1f..32f)
    if(value.effect==RasterEffect.MOTION) {
        Text(label("Direction",value.angle)+"°")
        Slider(value.angle,{onChange(value.copy(angle=it))},enabled=enabled,valueRange=-180f..180f)
    }
    if(value.effect==RasterEffect.PERSPECTIVE) {
        Text("Vanishing center: ${(value.centerX*100).roundToInt()}%, ${(value.centerY*100).roundToInt()}%")
        Slider(value.centerX,{onChange(value.copy(centerX=it))},enabled=enabled)
        Slider(value.centerY,{onChange(value.copy(centerY=it))},enabled=enabled)
    }
    if(value.effect==RasterEffect.BLOOM) {
        Text(label("Highlight threshold",value.threshold*100)+"%")
        Slider(value.threshold,{onChange(value.copy(threshold=it))},enabled=enabled)
        Text(label("Glow strength",value.intensity*100)+"%")
        Slider(value.intensity,{onChange(value.copy(intensity=it))},enabled=enabled,valueRange=0f..3f)
    }
    Text("Applies to the active paint layer and selection. Preview first; Apply keeps the result.",
        style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(vertical=4.dp))
}
