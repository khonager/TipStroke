package dev.tipstroke.ui
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import dev.tipstroke.core.effects.ColorizeMask
import kotlin.math.roundToInt

@Composable
fun ColorizeMaskControls(mask: ColorizeMask, enabled: Boolean, onChange: (ColorizeMask) -> Unit) {
    Text("Close gaps: ${mask.gap} analysis pixels")
    Slider(mask.gap.toFloat(),{onChange(mask.copy(gap=it.roundToInt()))},valueRange=0f..4f,steps=3,enabled=enabled)
    Text("Ink threshold: ${(mask.threshold*100).roundToInt()}%")
    Slider(mask.threshold,{onChange(mask.copy(threshold=it))},valueRange=.02f..1f,enabled=enabled)
}
