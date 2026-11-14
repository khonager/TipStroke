package dev.tipstroke.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tipstroke.core.model.LayerBlendMode
import dev.tipstroke.core.model.LayerId
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlin.math.abs

/** The centered row previews immediately; one opening is one native history transaction. */
@Composable
internal fun BlendModeScroller(
    layerId: LayerId,
    mode: LayerBlendMode,
    onPreview: (LayerBlendMode) -> Unit,
    onStart: () -> Unit,
    onFinish: () -> Unit,
) {
    val list = rememberLazyListState(initialFirstVisibleItemIndex = mode.ordinal)
    val scope = rememberCoroutineScope()
    val latestPreview by rememberUpdatedState(onPreview)
    val latestFinish by rememberUpdatedState(onFinish)
    DisposableEffect(layerId) {
        onStart()
        onDispose { latestFinish() }
    }
    LaunchedEffect(list, layerId) {
        snapshotFlow {
            val layout = list.layoutInfo
            val center = (layout.viewportStartOffset + layout.viewportEndOffset) / 2f
            layout.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2f - center) }?.index
        }.filterNotNull().distinctUntilChanged().collect { index ->
            latestPreview(LayerBlendMode.entries[index])
        }
    }
    Text("Scroll to preview · centered mode is active", color = Color(0xFFB8B9BE), fontSize = 11.sp)
    Box(Modifier.fillMaxWidth().height(180.dp).padding(vertical = 0.dp)) {
        Box(Modifier.align(Alignment.Center).fillMaxWidth().height(36.dp)
            .background(Color(0xFF234A68), RoundedCornerShape(8.dp))
            .border(1.dp, Color(0xFF66BFFF), RoundedCornerShape(8.dp)))
        LazyColumn(
            state = list,
            flingBehavior = rememberSnapFlingBehavior(list),
            contentPadding = PaddingValues(vertical = 72.dp),
            modifier = Modifier.fillMaxSize().semantics { contentDescription = "Blend mode previews" },
        ) {
            itemsIndexed(LayerBlendMode.entries, key = { _, item -> item.name }) { index, item ->
                Box(Modifier.fillMaxWidth().height(36.dp)
                    .semantics { selected = item == mode }
                    .clickable(role = Role.RadioButton) { scope.launch { list.animateScrollToItem(index) } },
                    contentAlignment = Alignment.Center) {
                    Text(item.label, color = if (item == mode) Color.White else Color(0xFFB8B9BE), fontSize = 14.sp)
                }
            }
        }
    }
}
