package dev.tipstroke.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.tipstroke.core.effects.RasterEffectSettings
import dev.tipstroke.drawing.android.DrawingSurface
import dev.tipstroke.ui.RasterEffectControls

@Composable
internal fun RasterAdjustmentsDialog(surface: DrawingSurface?, onDismiss: () -> Unit) {
    var settings by remember { mutableStateOf(RasterEffectSettings()) }
    var busy by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    DisposableEffect(surface) { onDispose { surface?.cancelRasterAdjustment() } }
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Box(Modifier.fillMaxSize().padding(12.dp),contentAlignment=Alignment.BottomCenter) {
            Surface(shape=MaterialTheme.shapes.large,tonalElevation=8.dp,modifier=Modifier.widthIn(max=400.dp).fillMaxWidth()) {
                Column(Modifier.heightIn(max=500.dp).verticalScroll(rememberScrollState()).padding(16.dp)) {
                    Text("Blur & bloom",style=MaterialTheme.typography.titleLarge)
                    RasterEffectControls(settings,!busy) { settings=it; preview=false; surface?.cancelRasterAdjustment() }
                    error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
                    if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                        TextButton(onClick=onDismiss) { Text("Cancel") }
                        TextButton(enabled=!busy && surface!=null,onClick={
                            busy=true; preview=false; error=null
                            surface?.previewRasterAdjustment(settings) { result -> busy=false;preview=result.isSuccess;error=result.exceptionOrNull()?.message }
                        }) { Text("Preview") }
                        Button(enabled=preview && !busy,onClick={
                            if(surface?.applyRasterAdjustment()==true) onDismiss() else { preview=false; error="Layer changed; preview again." }
                        }) { Text("Apply") }
                    }
                }
            }
        }
    }
}
