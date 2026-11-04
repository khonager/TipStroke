package dev.tipstroke.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
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
) {
    var dragTime by remember { mutableStateOf<Long?>(null) }
    var lastSeekAt by remember { mutableLongStateOf(0L) }
    var settings by remember { mutableStateOf(false) }
    var fps by remember(state.fps) { mutableStateOf(state.fps.toString()) }
    val frame = state.frames[state.selectedIndex]
    val ticks = (MediaTimeline.durationUs(frame, state.fps) * state.fps / 1_000_000.0).roundToInt().coerceAtLeast(1)
    var hold by remember(frame.id, frame.durationUs, state.fps) { mutableStateOf(ticks.toString()) }
    val time = dragTime ?: state.mediaPositionUs
    fun seconds(timeUs: Long) = String.format(Locale.ROOT, "%.2fs", timeUs / 1_000_000.0)
    Surface(modifier, color = Color(0xFF202125)) {
        Column(Modifier.padding(horizontal = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onPlayPause, enabled = state.playing || !state.loading,
                    modifier = Modifier.semantics { contentDescription = if (state.playing) "Pause video" else "Play video" }) {
                    Text(if (state.playing) "Pause" else "Play")
                }
                Text("${seconds(time)} / ${seconds(state.mediaDurationUs)}", color = Color.White, fontSize = 11.sp,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = { if (state.playing) onPlayPause(); settings = true }) { Text("${state.fps} FPS") }
                TextButton(onClick = onClose, modifier = Modifier.semantics { contentDescription = "Close media timeline" }) { Text("×") }
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
            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onAdd, enabled = !state.loading && !state.playing) { Text("Add drawing here") }
                TextButton(onClick = { onSeek((state.mediaPositionUs - 1_000_000L / state.fps).coerceAtLeast(0)) },
                    enabled = !state.playing && !state.loading, modifier = Modifier.semantics { contentDescription = "Previous drawing step" }) { Text("‹") }
                TextButton(onClick = { onSeek((state.mediaPositionUs + 1_000_000L / state.fps).coerceAtMost(state.mediaDurationUs - 1)) },
                    enabled = !state.playing && !state.loading, modifier = Modifier.semantics { contentDescription = "Next drawing step" }) { Text("›") }
                if (state.mediaDrawingActive) {
                    TextButton(onClick = { onHold((ticks - 1).coerceAtLeast(1)) }, enabled = !state.playing) { Text("−") }
                    Text("Hold $ticks · ${seconds(MediaTimeline.durationUs(frame, state.fps))}", color = Color.White, fontSize = 12.sp)
                    TextButton(onClick = { onHold((ticks + 1).coerceAtMost(600)) }, enabled = !state.playing) { Text("+") }
                } else Text("No drawing here", color = Color.LightGray, fontSize = 12.sp)
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 6.dp)) {
                itemsIndexed(state.frames, key = { _, drawing -> drawing.id.value }) { index, drawing ->
                    FilterChip(selected = state.mediaDrawingActive && index == state.selectedIndex,
                        onClick = { onSelect(index) }, label = { Text("${index + 1} · ${seconds(drawing.sourceTimeUs ?: 0)}", fontSize = 11.sp) })
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
