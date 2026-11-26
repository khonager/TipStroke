package dev.tipstroke.app

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import dev.tipstroke.drawing.android.DrawingLibrary
import dev.tipstroke.drawing.android.DrawingSummary
import dev.tipstroke.drawing.android.ExportFormat
import dev.tipstroke.drawing.android.ProjectTransfer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal class GalleryPreviewState(val drawing: DrawingSummary, val anchor: Rect, val initialScale: Float) {
    var scale by mutableFloatStateOf(initialScale)
    var pan by mutableStateOf(Offset.Zero)
    var fullscreen by mutableStateOf(false)
}

internal fun Modifier.galleryPinch(key: Any, begin: (Offset) -> Unit, moved: (Float, Offset) -> Unit, end: () -> Unit) =
    pointerInput(key) {
        awaitEachGesture {
            var active = false
            try {
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val fingers = event.changes.filter { it.pressed }
                    if (!active && fingers.size >= 2) {
                        begin((fingers[0].position + fingers[1].position) / 2f)
                        active = true
                    }
                    if (active) {
                        moved(event.calculateZoom(), event.calculatePan())
                        event.changes.forEach { it.consume() }
                    }
                } while (event.changes.any { it.pressed })
            } finally { if (active) end() }
        }
    }

@Composable
internal fun GalleryPreview(preview: GalleryPreviewState, library: DrawingLibrary, close: () -> Unit) {
    val context = LocalContext.current
    val drawing = preview.drawing
    val thumbnail = remember(drawing.id) { BitmapFactory.decodeFile(drawing.thumbnailFile.path)?.asImageBitmap() }
    val bitmap by produceState(initialValue = thumbnail, drawing.id, preview.fullscreen) {
        if (preview.fullscreen) {
            val detailed = withContext(Dispatchers.IO) {
                runCatching {
                    val file = File.createTempFile("gallery-preview-", ".png", context.cacheDir)
                    try {
                        val scale = (2048f / maxOf(drawing.widthPx, drawing.heightPx)).coerceAtMost(1f)
                        ProjectTransfer.exportMedia(context, library, listOf(drawing.id), android.net.Uri.fromFile(file), ExportFormat.PNG, null, scale)
                        BitmapFactory.decodeFile(file.path)?.asImageBitmap()
                    } finally { file.delete() }
                }.getOrNull()
            }
            if (detailed != null) value = detailed
        }
    }
    BackHandler(onBack = close)
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black.copy(alpha = preview.scale.coerceIn(.4f, 1f)))
        .clickable(enabled = true, onClickLabel = "Close preview", onClick = close)
        .galleryPinch(preview, {}, { zoom, pan ->
            preview.scale = (preview.scale * zoom).coerceIn(.15f, 8f)
            preview.pan = if (preview.scale > 1f) preview.pan + pan else Offset.Zero
        }, {
            if (preview.scale < .5f) close() else preview.scale = preview.scale.coerceAtLeast(1f)
        })) {
        val distance = preview.anchor.center - Offset(constraints.maxWidth / 2f, constraints.maxHeight / 2f)
        val weight = ((1f - preview.scale) / (1f - preview.initialScale).coerceAtLeast(.01f)).coerceIn(0f, 1f)
        bitmap?.let { image ->
            Image(image, drawing.name, Modifier.fillMaxSize().graphicsLayer {
                scaleX = preview.scale; scaleY = preview.scale
                translationX = distance.x * weight + preview.pan.x
                translationY = distance.y * weight + preview.pan.y
            }, contentScale = ContentScale.Fit)
        }
        if (preview.fullscreen) TextButton(onClick = close, modifier = Modifier.align(Alignment.TopEnd)) { Text("Close", color = Color.White) }
    }
}
