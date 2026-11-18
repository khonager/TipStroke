package dev.tipstroke.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.tipstroke.core.canvas.CanvasPreset
import dev.tipstroke.core.canvas.resolutionFavoriteCapacity
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sqrt

/** A settings control only: no drawing surfaces or pointer samples pass through here. */
@Composable
fun ResolutionBrowser(
    presets: List<CanvasPreset>, selectedId: String?, favorites: List<String>,
    onSelect: (CanvasPreset) -> Unit, onFavorites: (List<String>) -> Unit,
    modifier: Modifier = Modifier, customPreview: CanvasPreset? = null,
) {
    if (presets.isEmpty()) return
    val selected = presets.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
    val list = rememberLazyListState(selected)
    val scope = rememberCoroutineScope()
    val currentSelect by rememberUpdatedState(onSelect)
    val currentPresets by rememberUpdatedState(presets)
    // Scroll selection only while the user is moving the wheel. Programmatic selection
    // also scrolls the list, but must not select intermediate entries on its way there.
    var jumping by remember { mutableStateOf(false) }
    LaunchedEffect(list) {
        snapshotFlow {
            if (!list.isScrollInProgress || jumping) null else {
                val layout = list.layoutInfo
                val center = (layout.viewportStartOffset + layout.viewportEndOffset) / 2
                layout.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - center) }?.index
            }
        }.distinctUntilChanged().collect { index ->
            index?.let { currentPresets.getOrNull(it)?.let(currentSelect) }
        }
    }
    LaunchedEffect(selectedId) {
        if (!list.isScrollInProgress) list.scrollToItem(selected)
    }
    fun choose(preset: CanvasPreset) {
        onSelect(preset)
        scope.launch {
            jumping = true
            try { list.animateScrollToItem(presets.indexOf(preset)) } finally { jumping = false }
        }
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val capacity = resolutionFavoriteCapacity(maxWidth.value)
        val pinned = favorites.mapNotNull { id -> presets.find { it.id == id } }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Choose your paper", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth().height(252.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val previewModifier = Modifier.weight(1f).fillMaxHeight()
                if (selectedId == null) {
                    if (customPreview != null) PaperStack(presets + customPreview, presets.size, previewModifier)
                    else Box(previewModifier, contentAlignment = Alignment.Center) { Text("Enter a valid size") }
                } else PaperStack(presets, selected, previewModifier)
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    Surface(Modifier.align(Alignment.Center).fillMaxWidth().height(76.dp),
                        shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {}
                    LazyColumn(state = list, modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 88.dp),
                        flingBehavior = rememberSnapFlingBehavior(list)) {
                        itemsIndexed(presets, key = { _, p -> p.id }) { index, preset ->
                            Column(Modifier.fillMaxWidth().height(76.dp).clickable { choose(preset) }
                                .semantics { this.selected = index == selected && selectedId != null; contentDescription = "${preset.name}, ${preset.widthPx} by ${preset.heightPx} pixels${if (index == selected && selectedId != null) ", selected" else ""}" }
                                .padding(horizontal = 10.dp), verticalArrangement = Arrangement.Center) {
                                Text(preset.name, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelLarge)
                                Text("${preset.widthPx} × ${preset.heightPx}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            Text("Scroll sizes on the right · Preview is schematic", style = MaterialTheme.typography.bodySmall)
            if (pinned.isNotEmpty() && capacity > 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pinned.take(capacity).forEach { preset ->
                        OutlinedButton(onClick = { choose(preset) }, modifier = Modifier.weight(1f).semantics { contentDescription = "Favorite ${preset.name}, ${preset.widthPx} by ${preset.heightPx} pixels" },
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(preset.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                                Text("${preset.widthPx} × ${preset.heightPx}", maxLines = 1, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
            presets.find { it.id == selectedId }?.let { preset ->
                val isPinned = preset.id in favorites
                TextButton(onClick = {
                    onFavorites(if (isPinned) favorites - preset.id else favorites + preset.id)
                }, enabled = isPinned || pinned.size < capacity) {
                    Text(if (isPinned) "★ Remove shortcut" else if (pinned.size >= capacity) "Shortcut row full" else "☆ Pin this size")
                }
            }
            Text("${pinned.size.coerceAtMost(capacity)} / $capacity shortcuts · Hidden pins return when there’s room.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun PaperStack(presets: List<CanvasPreset>, selected: Int, modifier: Modifier) {
    BoxWithConstraints(modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp)).padding(12.dp)) {
        val largest = presets.maxOf { maxOf(it.widthPx, it.heightPx) }.toFloat()
        presets.forEachIndexed { index, preset ->
            val active = index == selected
            val distance = index - selected
            val turn by animateFloatAsState(if (active) 0f else (distance * 2f).coerceIn(-12f, 12f))
            val lift by animateFloatAsState(if (active) 0f else (distance * 5f).coerceIn(-28f, 28f))
            val longest = maxOf(preset.widthPx, preset.heightPx).toFloat()
            // Schematic scale keeps pixel-art sheets visible; aspect ratios are exact.
            val scale = (.35f + .65f * sqrt(longest / largest))
            val paperWidth = maxWidth * .78f * scale * (preset.widthPx / longest)
            val paperHeight = maxHeight * .68f * scale * (preset.heightPx / longest)
            // Use one common pixel-to-dp factor so wide and tall sheets retain their ratio.
            val factor = minOf(paperWidth.value / preset.widthPx, paperHeight.value / preset.heightPx)
            Surface(Modifier.align(Alignment.Center).offset(y = lift.dp)
                .zIndex(if (active) 1000f else -abs(distance).toFloat())
                .graphicsLayer { rotationZ = turn; rotationY = if (active) 0f else turn * 2f }
                .size((preset.widthPx * factor).dp, (preset.heightPx * factor).dp)
                .shadow(if (active) 8.dp else 2.dp),
                color = if (active) Color(0xFFFFFDF7) else Color(0xFFD9D5CB),
                border = BorderStroke(if (active) 2.dp else 1.dp, if (active) MaterialTheme.colorScheme.primary else Color(0xFFA7A49C))) {}
        }
        val preset = presets[selected]
        Column(Modifier.align(Alignment.BottomCenter), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${preset.widthPx} × ${preset.heightPx} px", style = MaterialTheme.typography.labelMedium)
            Text(preset.category, style = MaterialTheme.typography.labelSmall)
        }
    }
}
