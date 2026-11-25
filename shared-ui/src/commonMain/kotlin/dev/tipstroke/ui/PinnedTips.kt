package dev.tipstroke.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.*
import dev.tipstroke.core.model.BrushPreset

@Composable
fun TipFolderFace(overflow: List<BrushPreset>, extent: Dp) {
    Column(Modifier.size(extent).padding(3.dp)
        .background(Color(0xff36383c), MaterialTheme.shapes.small).padding(2.dp)) {
        repeat(2) { row ->
            Row(Modifier.fillMaxWidth().weight(1f)) {
                repeat(2) { col ->
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        overflow.getOrNull(row * 2 + col)?.let { TipSwatch(it, Modifier.fillMaxSize()) }
                    }
                }
            }
        }
    }
}

val LocalStrokePreview = staticCompositionLocalOf<(@Composable (BrushPreset, Modifier) -> Unit)?> { null }
val LocalEraserPreview = compositionLocalOf { false }

@Composable
fun TipSwatch(tip: BrushPreset, modifier: Modifier = Modifier) {
    val preview = LocalStrokePreview.current
    if (preview != null) preview(tip, modifier)
    else Spacer(modifier)
}

@Composable
internal fun TipName(tip: BrushPreset, selected: Boolean, favorite: Boolean, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        if (favorite) Text("★", Modifier.clearAndSetSemantics { contentDescription = "Favorite" },
            style = MaterialTheme.typography.bodySmall)
        Text(tip.displayName,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodySmall)
    }
}
