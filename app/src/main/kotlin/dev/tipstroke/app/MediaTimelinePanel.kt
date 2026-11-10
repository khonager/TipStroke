package dev.tipstroke.app

import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.clipRect
import dev.tipstroke.core.model.DrawingCoverage
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tipstroke.core.model.MediaTimeline
import dev.tipstroke.drawing.android.AnimationUiState
import kotlin.math.roundToInt
import java.util.Locale

/** Source time is independent of authored drawings; never request video thumbnails here. */
@Composable
internal fun MediaTimelinePanel(
    state: AnimationUiState,
    onSeek: (Long) -> Unit,
    onSelect: (Int) -> Unit,
    onAdd: () -> Unit,
    onDelete: () -> Unit,
    onHold: (Int) -> Unit,
    onFps: (Int) -> Unit,
    onPlayPause: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onOnion: (Boolean) -> Unit = {},
    onImportMedia: () -> Unit = {},
    onEnableVideoEdit: () -> Unit = {},
    onVideoEdit: (dev.tipstroke.core.model.VideoEdit, Long?) -> Unit = { _, _ -> },
    onUndoVideoEdit: (Boolean) -> Unit = {},
) {
    var dragTime by remember { mutableStateOf<Long?>(null) }
    var lastSeekAt by remember { mutableLongStateOf(0L) }
    var settings by remember { mutableStateOf(false) }
    var drawingTools by remember { mutableStateOf(false) }
    var fps by remember(state.fps) { mutableStateOf(state.fps.toString()) }
    val frame = state.frames[state.selectedIndex]
    val ticks = (MediaTimeline.durationUs(frame, state.fps) * state.fps / 1_000_000.0).roundToInt().coerceAtLeast(1)
    var hold by remember(frame.id, frame.durationUs, state.fps) { mutableStateOf(ticks.toString()) }
    val time = dragTime ?: state.mediaPositionUs
    val coverage = remember(state.frames, state.paintedFrameIds, state.fps, state.mediaDurationUs) {
        DrawingCoverage.spans(state.frames, state.paintedFrameIds, state.fps, state.mediaDurationUs)
    }
    val nextGap = remember(coverage, time, state.fps, state.mediaDurationUs) {
        DrawingCoverage.nextUndrawn(coverage, time, state.fps, state.mediaDurationUs)
    }
    fun seconds(timeUs: Long) = String.format(Locale.ROOT, "%.2fs", timeUs / 1_000_000.0)
    Surface(modifier, color = Color(0xFF202125)) {
        Column(Modifier.padding(horizontal = 12.dp)) {
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            VideoEditControls(state, onImportMedia, onEnableVideoEdit, onVideoEdit, onSeek, onUndoVideoEdit)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TipAction(if (state.playing) "Pause video" else "Play video",
                    if (state.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    enabled = state.playing || !state.loading, onClick = onPlayPause)
                Text("${seconds(time)} / ${seconds(state.mediaDurationUs)}", color = Color.White, fontSize = 11.sp,
                    modifier = Modifier.weight(1f))
                if (state.videoEdit != null) TipAction(if (drawingTools) "Hide drawings" else "Show drawings", Icons.Outlined.Brush, onClick = { drawingTools = !drawingTools })
                TipAction("Drawing timing · ${state.fps} FPS", Icons.Outlined.Tune, onClick = { if (state.playing) onPlayPause(); settings = true })
                TipAction("Close media timeline", Icons.Outlined.Close, onClick = onClose)
            }
            Slider(value = time.toFloat(), onValueChange = {
                if (dragTime == null && state.playing) onPlayPause()
                dragTime = it.toLong()
                // The native player coalesces seeks. UI state holds the thumb until release.
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastSeekAt >= 60) { lastSeekAt = now; onSeek(it.toLong()) }
            }, onValueChangeFinished = { dragTime?.let(onSeek); dragTime = null },
                valueRange = 0f..(state.mediaDurationUs - 1).coerceAtLeast(1).toFloat(),
                modifier = Modifier.fillMaxWidth().height(32.dp).semantics { contentDescription = "Scrub video" })
            Canvas(Modifier.fillMaxWidth().height(22.dp).semantics { contentDescription = "Drawing coverage: colored spans are painted, hatched spans are undrawn" }) {
                drawRect(Color(0xFF36383E))
                clipRect {
                    var x = -size.height
                    while (x < size.width) {
                        drawLine(Color(0xFF777A82), Offset(x, size.height), Offset(x + size.height, 0f), 1.dp.toPx())
                        x += 10.dp.toPx()
                    }
                    val duration = state.mediaDurationUs.coerceAtLeast(1).toDouble()
                    coverage.forEach { span ->
                        val left = (span.startUs / duration * size.width).toFloat()
                        val right = (span.endUs / duration * size.width).toFloat()
                        drawRect(Color(0xFF59B89B), Offset(left, 0f), Size((right - left).coerceAtLeast(1f), size.height))
                    }
                    val cursor = (time / duration * size.width).toFloat()
                    drawLine(Color.White, Offset(cursor, 0f), Offset(cursor, size.height), 2.dp.toPx())
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (state.coveragePending) "Checking drawings…" else "Color: painted · Hatch: undrawn", color = Color.LightGray, fontSize = 11.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { nextGap?.let(onSeek) }, enabled = nextGap != null && !state.coveragePending && !state.loading && !state.playing) {
                    Text(if (nextGap == null && !state.coveragePending) "All drawn" else "Next undrawn")
                }
            }
            if (state.videoEdit == null || drawingTools) {
            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                TipAction("Add drawing here", Icons.Outlined.Add, enabled = !state.loading && !state.playing, onClick = onAdd)
                TipAction("Previous drawing step", Icons.Outlined.SkipPrevious, onClick = { onSeek((state.mediaPositionUs - 1_000_000L / state.fps).coerceAtLeast(0)) },
                    enabled = !state.playing && !state.loading)
                TipAction("Next drawing step", Icons.Outlined.SkipNext, onClick = { onSeek((state.mediaPositionUs + 1_000_000L / state.fps).coerceAtMost(state.mediaDurationUs - 1)) },
                    enabled = !state.playing && !state.loading)
                if (state.mediaDrawingActive) {
                    TipAction("Shorten drawing hold", Icons.Outlined.Remove, enabled = !state.playing, onClick = { onHold((ticks - 1).coerceAtLeast(1)) })
                    Text("Hold $ticks · ${seconds(MediaTimeline.durationUs(frame, state.fps))}", color = Color.White, fontSize = 12.sp)
                    TipAction("Extend drawing hold", Icons.Outlined.Add, enabled = !state.playing, onClick = { onHold((ticks + 1).coerceAtMost(600)) })
                } else Text("No drawing here", color = Color.LightGray, fontSize = 12.sp)
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 6.dp)) {
                itemsIndexed(state.frames, key = { _, drawing -> drawing.id.value }) { index, drawing ->
                    FilterChip(selected = state.mediaDrawingActive && index == state.selectedIndex,
                        onClick = { onSelect(index) }, modifier = Modifier.semantics { contentDescription = "Drawing ${index + 1} at ${seconds(drawing.sourceTimeUs ?: 0)}" }, label = { Text(if (drawing.id in state.paintedFrameIds) "● Painted" else "○ Empty", fontSize = 11.sp) })
                }
            }
            }
        }
    }
    if (settings) AlertDialog(onDismissRequest = { settings = false }, title = { Text("Drawing timing") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("FPS sets the grid for new drawings and hold steps. Existing timings and video speed stay unchanged.")
                OutlinedTextField(fps, { if (it.length <= 2 && it.all(Char::isDigit)) fps = it }, label = { Text("Drawing FPS (1–60)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Onion skins", Modifier.weight(1f))
                    Switch(checked = state.onionBefore > 0 || state.onionAfter > 0, onCheckedChange = onOnion)
                }
                if (state.mediaDrawingActive) {
                    OutlinedTextField(hold, { if (it.length <= 3 && it.all(Char::isDigit)) hold = it }, label = { Text("Hold in drawing frames (1–600)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                    Text("A new drawing replaces the previous one at its start time.", fontSize = 12.sp)
                    TextButton(onClick = { onDelete(); settings = false }, enabled = state.frames.size > 1 && !state.playing) { Text("Delete selected drawing") }
                }
            }
        }, confirmButton = {
            TextButton(onClick = {
                // FPS alone preserves exact existing duration. An edited hold uses the new grid.
                fps.toIntOrNull()?.takeIf { it in 1..60 }?.let(onFps)
                if (hold != ticks.toString()) hold.toIntOrNull()?.takeIf { it in 1..600 }?.let(onHold)
                settings = false
            }, enabled = fps.toIntOrNull() in 1..60 && (!state.mediaDrawingActive || hold.toIntOrNull() in 1..600)) { Text("Apply") }
        }, dismissButton = { TextButton(onClick = { settings = false }) { Text("Cancel") } })
}
