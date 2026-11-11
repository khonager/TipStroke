package dev.tipstroke.app

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import dev.tipstroke.core.model.BrushPreset
import dev.tipstroke.core.model.RgbaColor
import dev.tipstroke.drawing.android.BrushStrokePreviewView
import kotlinx.coroutines.delay

/** Stylus/mouse hover and touch long-press share the same label; no pointer samples enter app state. */
@Composable
internal fun TipHint(label: String, beside: Boolean = false, content: @Composable (() -> Unit) -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val hovering by interactions.collectIsHoveredAsState()
    var held by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(hovering, held) {
        visible = false
        if (hovering || held) {
            if (!held) delay(650)
            visible = true
            if (held) { delay(2000); held = false }
        }
    }
    val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
    val position = remember(beside, gap) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val x = if (beside) {
                    if (anchorBounds.right + gap + popupContentSize.width <= windowSize.width) anchorBounds.right + gap
                    else anchorBounds.left - gap - popupContentSize.width
                } else anchorBounds.center.x - popupContentSize.width / 2
                val y = if (beside) anchorBounds.center.y - popupContentSize.height / 2
                    else if (anchorBounds.top >= popupContentSize.height + gap) anchorBounds.top - popupContentSize.height - gap
                    else anchorBounds.bottom + gap
                return IntOffset(x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
                    y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)))
            }
        }
    }
    Box(Modifier.hoverable(interactions)) {
        content { held = true }
        if (visible) Popup(popupPositionProvider = position) {
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface, shadowElevation = 4.dp) {
                Text(label, Modifier.widthIn(max = 280.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                    maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
internal fun TipAction(label: String, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    TipHint(label) { showHint ->
        Box(Modifier.size(48.dp).clip(MaterialTheme.shapes.small)
            .combinedClickable(enabled = enabled, role = Role.Button, onLongClick = showHint, onClick = onClick)
            .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = LocalContentColor.current.copy(alpha = if (enabled) 1f else .38f))
        }
    }
}

internal val LocalTipPreviewColor = compositionLocalOf { RgbaColor(1f, 1f, 1f) }

internal fun tipPreviewBackground(@Suppress("UNUSED_PARAMETER") color: RgbaColor): Color = Color(0xFF303238)

/** Every thumbnail uses the same native raster painter, including imported shape/grain masks. */
@Composable
internal fun TipStrokeThumbnail(preset: BrushPreset, modifier: Modifier = Modifier, actualSize: Boolean = false,
    color: RgbaColor = LocalTipPreviewColor.current, erasing: Boolean = false) {
    AndroidView(factory = { BrushStrokePreviewView(it).apply { importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO } },
        update = { it.setPreview(preset, erasing, preset.opacity, color, usePresetSize = actualSize, thumbnail = true) }, modifier = modifier.background(tipPreviewBackground(color), MaterialTheme.shapes.extraSmall))
}

/** Pure endpoint presets never change the draft or invoke the user's value callback. */
@Composable
internal fun TipPreviewSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>,
    previewAt: (Float) -> BrushPreset, steps: Int = 0, actualSize: Boolean = false,
    valueLabel: String = "%.2f".format(value), erasing: Boolean = false, onValue: (Float) -> Unit) {
    Column {
        Text("$label · $valueLabel", style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            EndpointPreview("$label minimum · ${"%.2f".format(range.start)}", previewAt(range.start), actualSize, erasing)
            Slider(value.coerceIn(range), onValue, valueRange = range, steps = steps,
                modifier = Modifier.weight(1f).semantics { contentDescription = label })
            EndpointPreview("$label maximum · ${"%.2f".format(range.endInclusive)}", previewAt(range.endInclusive), actualSize, erasing)
        }
    }
}

@Composable
private fun EndpointPreview(label: String, preset: BrushPreset, actualSize: Boolean, erasing: Boolean) {
    TipHint(label) { showHint ->
        Box(Modifier.size(56.dp, 36.dp).clip(MaterialTheme.shapes.small).background(Color(0xFF303238))
            .combinedClickable(onClick = showHint, onLongClick = showHint).semantics { contentDescription = label }) {
            TipStrokeThumbnail(preset, Modifier.fillMaxSize(), actualSize, erasing = erasing)
        }
    }
}
