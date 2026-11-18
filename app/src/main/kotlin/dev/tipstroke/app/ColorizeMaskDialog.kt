package dev.tipstroke.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import dev.tipstroke.drawing.android.DrawingSurface

@Composable
internal fun ColorizeMaskDialog(surface: DrawingSurface?, onHints: (Boolean) -> Unit, onDismiss: () -> Unit) {
    var mask by remember(surface) { mutableStateOf(surface?.colorizeMaskSettings()) }
    var busy by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    DisposableEffect(surface) { onDispose { surface?.cancelRasterAdjustment() } }
    AlertDialog(onDismissRequest=onDismiss,title={Text("Colorize mask")},text={
        Column(Modifier.heightIn(max=360.dp).verticalScroll(rememberScrollState())) {
            Text("Draw color hints, then Preview and Apply. Keep line art on a transparent layer, or use Multiply for paper-white line art.")
            if(mask==null) Text("Create adds editable hints and a generated color layer beneath the active line art. Available for still paint layers.")
            else {
                Text("Apply replaces generated color. Unseeded regions stay empty; magenta hints mark transparency. Analysis uses up to 1024 pixels per side.")
                dev.tipstroke.ui.ColorizeMaskControls(mask!!,!busy) { mask=it;preview=false;surface?.cancelRasterAdjustment() }
                Row {
                    TextButton(enabled=!busy,onClick={if(surface?.editColorizeHints()==true) onHints(false) else error="Hints layer is missing."}) {Text("Edit hints")}
                    TextButton(enabled=!busy,onClick={if(surface?.editColorizeHints()==true) onHints(true) else error="Hints layer is missing."}) {Text("Transparent hint")}
                }
            }
            error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
            if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    },dismissButton={TextButton(onClick=onDismiss){Text("Cancel")}},confirmButton={
        if(mask==null) Button(onClick={
            if(surface?.createColorizeMask()==true) onHints(false) else error="Choose a paint layer in a still drawing with room for two more layers."
        }){Text("Create mask")}
        else Row {
            TextButton(enabled=!busy,onClick={busy=true;preview=false;error=null
                surface?.previewColorizeMask(mask!!) { result -> busy=false;preview=result.isSuccess;error=result.exceptionOrNull()?.message }
            }) {Text("Preview")}
            Button(enabled=preview && !busy,onClick={if(surface?.applyRasterAdjustment()==true) onDismiss() else {preview=false;error="Inputs changed; preview again."}}){Text("Apply")}
        }
    })
}
