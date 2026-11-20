package dev.tipstroke.ui

import androidx.compose.foundation.BorderStroke
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

/** Keeps the original pointer on the dock while its menu is in a separate popup window. */
@Composable
fun HoldToolPicker(
    labels: List<String>, horizontal: Boolean, holdEnabled: Boolean, extent: Dp, reopen: Int,
    stateLabel: String, selected: Boolean, onTap: () -> Unit, onLegacyHold: () -> Unit,
    description: String = "Drawing tools", onClosed: () -> Unit = {},
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
    var page by remember(labels) { mutableIntStateOf(0) }
    // Leave space for the caption and navigation in landscape windows.
    val paged = labels.size > 16
    val indices = if (paged) labels.indices.drop(page * 12).take(12) else labels.indices.toList()
    val cells = buildList {
        addAll(indices)
        if (paged && page > 0) add(-1)
        if (paged && indices.last() < labels.lastIndex) add(-2)
    }
    fun label(index: Int) = when (index) { -1 -> "Previous page"; -2 -> "Next page"; else -> labels[index] }
    fun close() { open = false; hovered = null; hint = null; page = 0; onClosed() }
    LaunchedEffect(reopen) { if (reopen > 0) open = true }
    fun activate(index: Int) {
        if (index >= 0) { close(); choose(index) }
        else { page += if (index == -1) -1 else 1; hovered = null; hint = null }
    }
    val currentCells by rememberUpdatedState(cells)
    val currentActivate by rememberUpdatedState(::activate)
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
                    var released = false
                    try {
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (event.changes.size != 1 || change.isConsumed) break
                            if (open) {
                                val local = change.position - position.relativeOrigin - Offset(0f, 24.dp.roundToPx().toFloat())
                                hovered = if (local.x >= 0 && local.x < columns * cellPx && local.y >= 0)
                                    currentCells.getOrNull((local.y / cellPx).toInt() * columns + (local.x / cellPx).toInt())
                                else null
                                if (!change.pressed) {
                                    released = true
                                    val target = hovered
                                    change.consume()
                                    if (target == null) close() else currentActivate(target)
                                }
                            }
                        } while (change.pressed)
                    } finally {
                        pointerDown = false
                        if (open && !released) close()
                    }
                }
            }.combinedClickable(role = Role.Button,
                onClick = { if (holdEnabled) onTap() else open = true },
                onLongClickLabel = if (holdEnabled) "Open ${description.lowercase()}" else "Reselect previous area",
                onLongClick = { if (holdEnabled) open = true else onLegacyHold() })
                .semantics { contentDescription = description; stateDescription = stateLabel; this.selected = active },
                contentAlignment = Alignment.Center) { anchorIcon() }
        }
        if (open) Popup(position, onDismissRequest = { close() }, properties = PopupProperties(focusable = !pointerDown)) {
            Surface(shape = MaterialTheme.shapes.medium, color = Color(0xFF202125), contentColor = Color.White,
                border = BorderStroke(1.dp, Color(0xFF45474D))) {
                Column {
                    Box(Modifier.width(cell * columns).height(24.dp), contentAlignment = Alignment.Center) {
                        Text((hovered ?: hint)?.let(::label) ?: description, maxLines = 1, style = MaterialTheme.typography.labelMedium)
                    }
                    cells.chunked(columns).forEach { row ->
                        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides LayoutDirection.Ltr) { Row {
                            row.forEach { index ->
                                val interaction = remember { MutableInteractionSource() }
                                val pointerOver by interaction.collectIsHoveredAsState()
                                LaunchedEffect(pointerOver) {
                                    if (pointerOver) hint = index
                                    else if (hint == index && hovered != index) hint = null
                                }
                                Box(Modifier.size(cell).hoverable(interaction)
                                    .background(if (hovered == index || pointerOver) Color(0xFF834337) else Color.Transparent)
                                    .combinedClickable(role = Role.Button, onLongClick = { hint = index },
                                        onClick = { activate(index) })
                                    .semantics { contentDescription = label(index) },
                                    contentAlignment = Alignment.Center) { if (index < 0) Text(if (index == -1) "‹" else "›") else toolIcon(index) }
                            }
                        } }
                    }

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
