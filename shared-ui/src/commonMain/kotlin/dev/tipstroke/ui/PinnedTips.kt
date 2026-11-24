package dev.tipstroke.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.*
import dev.tipstroke.core.editor.NativeBrush
import dev.tipstroke.core.model.*
import dev.tipstroke.core.drawing.Graphite
import kotlin.math.*

/** A folder consumes one real slot, and contains only the pins after the direct shortcuts. */
@Composable
fun PinnedTipCells(tips: List<BrushPreset>, slots: Int, extent: Dp, onPick: (BrushPreset) -> Unit,
    direct: @Composable (BrushPreset) -> Unit) {
    if (slots < 1) return
    val directCount = if (tips.size <= slots) tips.size else slots - 1
    tips.take(directCount).forEach { key(it.id) { direct(it) } }
    val overflow = tips.drop(directCount)
    if (overflow.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Column(Modifier.size(extent).clickable(role = Role.Button) { open = true }
        .semantics { contentDescription = "More pinned tips" }.padding(3.dp)
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
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text("Pinned tips") }, text = {
        LazyColumn(Modifier.heightIn(max = 440.dp)) {
            items(overflow, key = { it.id.value }) { tip ->
                Row(Modifier.fillMaxWidth().clickable { open = false; onPick(tip) }.heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    TipSwatch(tip, Modifier.size(44.dp))
                    Text(tip.displayName, Modifier.padding(start = 8.dp))
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } })
}

/** Small cached white-on-charcoal specimen with the preset's shape erased into its broad end. */
@Composable
fun TipSwatch(tip: BrushPreset, modifier: Modifier = Modifier) {
    val coveragePaths = remember(tip) {
        val side = 64
        val native = NativeBrush(NativeBrush(tip).eraserPreset())
        val shape = native.shape()
        val grain = if (tip.engine == BrushEngine.PENCIL) Graphite.grain() else native.grain()
        val result = Array(16) { Path() }
        val angle = (tip.stamp?.rotationDegrees ?: tip.ink?.rotationDegrees ?: 0f) * PI / 180
        val cosAngle = cos(angle)
        val sinAngle = sin(angle)
        for (y in 0 until side) for (x in 0 until side) {
            val px = (x + .5f - 32) / 27
            val py = (y + .5f - 32) / 27
            val u = px * .94f - py * .342f
            val v = px * .342f + py * .94f
            if (u*u + v*v > 1 || (v < 0 && (u+.5f)*(u+.5f)+v*v > .25f) ||
                (v >= 0 && (u-.5f)*(u-.5f)+v*v < .25f)) continue
            val dx = (u + .5f) / .26f
            val dy = v / .26f
            val sx = dx*cosAngle + dy*sinAngle
            val sy = (-dx*sinAngle + dy*cosAngle) / native.aspectRatio
            val gx = x % grain.width
            val gy = y % grain.height
            val texture = grain.coverage(gy * grain.width + gx) / 255f
            val erase = if (abs(sx) < 1 && abs(sy) < 1) {
                val i = ((sx+1)*shape.width/2).toInt().coerceAtMost(shape.width-1)
                val j = ((sy+1)*shape.height/2).toInt().coerceAtMost(shape.height-1)
                shape.coverage(j*shape.width+i)/255f * texture * tip.opacity
            } else 0f
            val band = (texture * (1f-erase) * tip.opacity * 15).roundToInt()
            if (band > 0) result[band].addRect(Rect(x.toFloat(), y.toFloat(), x+1f, y+1f))
        }
        result
    }
    Canvas(modifier.background(Color(0xff292a2e), MaterialTheme.shapes.extraSmall)) {
        val side = size.minDimension
        withTransform({
            translate((size.width-side)/2, (size.height-side)/2)
            scale(side/64, side/64, Offset.Zero)
        }) {
            for (band in 1..15) drawPath(coveragePaths[band], Color.White, alpha = band/15f)
        }
    }
}
