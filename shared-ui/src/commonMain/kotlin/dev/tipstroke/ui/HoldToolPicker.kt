package dev.tipstroke.ui

import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay

/** Keeps the original pointer on the dock while its menu is in a separate popup window. */
@Composable
fun HoldToolPicker(
    labels: List<String>, horizontal: Boolean, holdEnabled: Boolean, extent: Dp, reopen: Int,
    stateLabel: String, selected: Boolean, onTap: () -> Unit, onLegacyHold: () -> Unit,
    onChoose: (Int) -> Unit, anchorIcon: @Composable () -> Unit, toolIcon: @Composable (Int) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var pointerDown by remember { mutableStateOf(false) }
    var hovered by remember { mutableStateOf<Int?>(null) }
    var hint by remember { mutableStateOf<Int?>(null) }
    val position = remember(horizontal) { ToolMenuPosition(horizontal) }
    val choose by rememberUpdatedState(onChoose)
    val columns = 4
    val cell = 48.dp
    fun close() { open = false; hovered = null; hint = null }
    LaunchedEffect(reopen) { if (reopen > 0) open = true }
    LaunchedEffect(hovered) {
        hint = null
        if (hovered != null) { delay(450); hint = hovered }
    }
    Box {
        val active = selected || open
        Surface(shape = MaterialTheme.shapes.small,
            color = if (active) Color(0xFFF4F4F2) else Color.Transparent,
            contentColor = if (active) Color(0xFF17181B) else Color.White) {
            Box(Modifier.size(extent).pointerInput(holdEnabled, labels, position) {
                if (!holdEnabled) return@pointerInput
                val cellPx = cell.roundToPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    pointerDown = true
                    try {
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (event.changes.size != 1 || change.isConsumed) break
                            if (open) {
                                val local = change.position - position.relativeOrigin
                                hovered = if (local.x >= 0 && local.x < columns * cellPx && local.y >= 0)
                                    ((local.y / cellPx).toInt() * columns + (local.x / cellPx).toInt()).takeIf { it in labels.indices }
                                else null
                                if (!change.pressed) {
                                    val target = hovered
                                    change.consume()
                                    close()
                                    if (target != null) choose(target)
                                }
                            }
                        } while (change.pressed)
                    } finally {
                        pointerDown = false
                        if (open) close()
                    }
                }
            }.combinedClickable(role = Role.Button,
                onClick = { if (holdEnabled) onTap() else open = true },
                onLongClickLabel = if (holdEnabled) "Open drawing tools" else "Reselect previous area",
                onLongClick = { if (holdEnabled) open = true else onLegacyHold() })
                .semantics { contentDescription = "Drawing tools"; stateDescription = stateLabel; this.selected = active },
                contentAlignment = Alignment.Center) { anchorIcon() }
        }
        if (open) Popup(position, onDismissRequest = { close() }, properties = PopupProperties(focusable = !pointerDown)) {
            Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 4.dp) {
                Column {
                    labels.indices.chunked(columns).forEach { row ->
                        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides LayoutDirection.Ltr) { Row {
                            row.forEach { index ->
                                val interaction = remember { MutableInteractionSource() }
                                val pointerOver by interaction.collectIsHoveredAsState()
                                LaunchedEffect(pointerOver) {
                                    if (pointerOver) { delay(450); hint = index }
                                    else if (hint == index && hovered != index) hint = null
                                }
                                Box(Modifier.size(cell).hoverable(interaction)
                                    .background(if (hovered == index) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                                    .combinedClickable(role = Role.Button, onLongClick = { hint = index },
                                        onClick = { close(); choose(index) })
                                    .semantics { contentDescription = labels[index] },
                                    contentAlignment = Alignment.Center) { toolIcon(index) }
                            }
                        } }
                    }
                    // Reserve the caption line so dwelling never moves the targets under the pointer.
                    Text(hint?.let(labels::get) ?: "", Modifier.width(cell * columns).padding(horizontal = 6.dp),
                        maxLines = 1, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

private class ToolMenuPosition(private val horizontal: Boolean) : PopupPositionProvider {
    var relativeOrigin = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
        private set

    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection,
        popupContentSize: IntSize): IntOffset {
        val width = popupContentSize.width
        val height = popupContentSize.height
        val x = when {
            horizontal -> anchorBounds.center.x - width / 2
            anchorBounds.right + width <= windowSize.width -> anchorBounds.right
            else -> anchorBounds.left - width
        }.coerceIn(0, (windowSize.width - width).coerceAtLeast(0))
        val y = when {
            !horizontal -> anchorBounds.center.y - height / 2
            anchorBounds.top >= height -> anchorBounds.top - height
            else -> anchorBounds.bottom
        }.coerceIn(0, (windowSize.height - height).coerceAtLeast(0))
        relativeOrigin = Offset((x - anchorBounds.left).toFloat(), (y - anchorBounds.top).toFloat())
        return IntOffset(x, y)
    }
}
