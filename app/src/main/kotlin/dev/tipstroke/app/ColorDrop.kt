package dev.tipstroke.app

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import dev.tipstroke.core.model.RgbaColor

internal val LocalColorDrop = staticCompositionLocalOf<(Offset?, RgbaColor, Boolean) -> Unit> { { _, _, _ -> } }

/** Keep taps for the picker; movement beyond touch slop starts a color drop. */
internal fun Modifier.colorDrop(color: RgbaColor): Modifier = composed {
    val callback by rememberUpdatedState(LocalColorDrop.current)
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var position by remember { mutableStateOf(Offset.Zero) }
    semantics { stateDescription = "Drag onto the canvas to fill the selected paint layer" }
        .onGloballyPositioned { coordinates = it }.pointerInput(color) {
        detectDragGestures(
            onDragStart = { position = it; callback(coordinates?.localToWindow(it), color, false) },
            onDrag = { change, _ ->
                change.consume()
                position = change.position
                callback(coordinates?.localToWindow(position), color, false)
            },
            onDragEnd = { callback(coordinates?.localToWindow(position), color, true) },
            onDragCancel = { callback(null, color, false) },
        )
    }
}
