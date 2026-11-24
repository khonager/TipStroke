package dev.tipstroke.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.hypot

/** A thumbnail and its edge drop share a single gesture target throughout the transition. */
@Composable
fun CanvasPreviewOverlay(
    aspectRatio: Float,
    onRemove: () -> Unit,
    trashIcon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    edgeGestureModifier: Modifier = Modifier,
    artwork: @Composable () -> Unit,
) {
    val remove by rememberUpdatedState(onRemove)
    BoxWithConstraints(modifier, contentAlignment = AbsoluteAlignment.TopLeft) {
        val areaW = maxWidth.value
        val areaH = maxHeight.value
        if (areaW < 48 || areaH < 48) return@BoxWithConstraints
        // Letterbox unusually thin documents so the thumbnail still has a draggable center.
        val ratio = aspectRatio.coerceIn(0.4f, 2.5f)
        val maxW = minOf(areaW, areaH * ratio, 420f)
        val minW = minOf(maxW, maxOf(64f, 56f * ratio))
        var preferredW by rememberSaveable { mutableFloatStateOf(minOf(areaW * .32f, 180f, 180f * ratio)) }
        var left by rememberSaveable { mutableFloatStateOf(12f) }
        var top by rememberSaveable { mutableFloatStateOf(76f) }
        var tucked by rememberSaveable { mutableStateOf(false) }
        var rightEdge by rememberSaveable { mutableStateOf(false) }
        var dragging by remember { mutableStateOf(false) }
        var overTrash by remember { mutableStateOf(false) }

        val width = preferredW.coerceIn(minW, maxW)
        val height = width / ratio
        val x = left.coerceIn(0f, areaW - width)
        val y = top.coerceIn(0f, (areaH - height).coerceAtLeast(0f))
        val cornerRadius = with(LocalDensity.current) { 7.dp.toPx() }
        val fold by animateFloatAsState(if (tucked) 1f else 0f, tween(210))
        fun mix(a: Float, b: Float) = a + (b - a) * fold
        val visibleW = mix(width, 11f)
        val visibleH = mix(height, 28f)
        val hitW = maxOf(40f, visibleW)
        val hitH = maxOf(48f, visibleH)
        val hitX = mix(x, if (rightEdge) areaW - hitW else 0f)
        val hitY = (y + height / 2 - hitH / 2).coerceIn(0f, areaH - hitH)
        val targetY = areaH - 36f

        if (dragging) {
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp).size(52.dp)
                .background(if (overTrash) Color(0xffd54b40) else Color(0xee353538), CircleShape),
                contentAlignment = Alignment.Center) { trashIcon() }
        }

        val currentOrigin by rememberUpdatedState(hitX to hitY)
        Box(Modifier.absoluteOffset(hitX.dp, hitY.dp).size(hitW.dp, hitH.dp)
            .then(if (tucked) edgeGestureModifier else Modifier)
            .semantics {
                contentDescription = if (tucked) "Tucked canvas preview" else "Canvas preview"
                if (tucked) onClick("Pull out preview") { tucked = false; true }
            }
            .pointerInput(areaW, areaH, ratio) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    val wasTucked = tucked
                    val startW = preferredW.coerceIn(minW, maxW)
                    val startH = startW / ratio
                    val (originX, originY) = currentOrigin
                    val startX = left.coerceIn(0f, areaW - startW)
                    val startY = top.coerceIn(0f, (areaH - startH).coerceAtLeast(0f))
                    val localX = down.position.x.toDp().value
                    val localY = down.position.y.toDp().value
                    val corner = minOf(18f, startW / 4, startH / 4)
                    val fromLeft = localX < corner
                    val fromTop = localY < corner
                    val resizing = !wasTucked && (fromLeft || localX > startW - corner) &&
                        (fromTop || localY > startH - corner)
                    var dx = 0f
                    var dy = 0f
                    var moved = false
                    var edge: Boolean? = null
                    try {
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (change.isConsumed) break
                            val delta = change.positionChange()
                            dx += delta.x.toDp().value
                            dy += delta.y.toDp().value
                            change.consume()
                            if (!change.pressed) {
                                if (dragging && overTrash) remove()
                                else if (dragging && edge != null) {
                                    rightEdge = edge
                                    tucked = true
                                }
                                break
                            }
                            if (!moved && hypot(dx, dy) < viewConfiguration.touchSlop.toDp().value) continue
                            moved = true
                            if (resizing) {
                                val sx = if (fromLeft) -dx else dx
                                val sy = if (fromTop) -dy else dy
                                val roomX = if (fromLeft) startX + startW else areaW - startX
                                val roomY = if (fromTop) startY + startH else areaH - startY
                                val limit = minOf(maxW, roomX, roomY * ratio).coerceAtLeast(minW)
                                val newW = (startW + (sx + sy / ratio) / (1 + 1 / (ratio * ratio))).coerceIn(minW, limit)
                                preferredW = newW
                                left = if (fromLeft) startX + startW - newW else startX
                                top = if (fromTop) startY + startH - newW / ratio else startY
                            } else {
                                if (tucked) {
                                    if ((if (rightEdge) -dx else dx) < 6f) continue
                                    tucked = false
                                }
                                dragging = true
                                val fingerX = originX + localX + dx
                                val fingerY = originY + localY + dy
                                overTrash = hypot(fingerX - areaW / 2, fingerY - targetY) <= 34f
                                val rawX = if (wasTucked) fingerX - startW / 2 else startX + dx
                                left = rawX.coerceIn(0f, areaW - startW)
                                top = (startY + dy).coerceIn(0f, (areaH - startH).coerceAtLeast(0f))
                                edge = when {
                                    rawX < -10f && dx < 0f -> false
                                    rawX + startW > areaW + 10f && dx > 0f -> true
                                    else -> null
                                }
                            }
                        }
                    } finally {
                        dragging = false
                        overTrash = false
                    }
                }
            }, contentAlignment = if (rightEdge) AbsoluteAlignment.CenterRight else AbsoluteAlignment.CenterLeft) {
            val shape = when {
                fold <= .001f -> RoundedCornerShape(7.dp)
                else -> GenericShape { size, _ ->
                    val w = size.width
                    val h = size.height
                    val radius = minOf(cornerRadius, w / 2, h / 2) * (1f - fold)
                    fun px(distance: Float) = if (rightEdge) w - distance else distance
                    // The outside corners flow into a single rounded bead against the edge.
                    val inset = radius + h * .47f * fold
                    val shoulder = (w - radius) * (1f - fold)
                    moveTo(px(0f), radius)
                    quadraticTo(px(0f), 0f, px(radius), 0f)
                    lineTo(px(shoulder), 0f)
                    quadraticTo(px(w), 0f, px(w), inset)
                    lineTo(px(w), h - inset)
                    quadraticTo(px(w), h, px(shoulder), h)
                    lineTo(px(radius), h)
                    quadraticTo(px(0f), h, px(0f), h - radius)
                    close()
                }
            }
            Box(Modifier.size(visibleW.dp, visibleH.dp).shadow(2.dp, shape).clip(shape)
                .background(Color(0xff45464a))) {
                if (fold < 1f) {
                    artwork()
                    if (fold > 0f) Box(Modifier.fillMaxSize().background(Color(0xff45464a).copy(alpha = fold)))
                }
            }
        }
    }
}
