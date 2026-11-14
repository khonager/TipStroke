package dev.tipstroke.app

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tipstroke.core.model.PhotoEditTool

/** Shared image/selection controls: icons, small captions, and accessible names. */
@Composable
internal fun TransformIcon(label: String, icon: ImageVector, active: Boolean = false,
    enabled: Boolean = true, rotation: Float = 0f, onClick: () -> Unit) {
    TipHint(label) { showHint ->
        Surface(color = if (active) Color(0xFF167DDF) else Color.Transparent, shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.width(72.dp).heightIn(min=56.dp)
                .combinedClickable(enabled=enabled, role=Role.Button, onClick=onClick, onLongClick=showHint)
                .semantics { contentDescription=label; selected=active }
                .padding(horizontal=3.dp,vertical=6.dp),
                horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(3.dp)) {
                Icon(icon,null,Modifier.size(24.dp).rotate(rotation),tint=if(enabled) Color.White else Color.Gray)
                Text(label,color=if(enabled) Color.White else Color.Gray,fontSize=10.sp,lineHeight=12.sp,textAlign=TextAlign.Center)
            }
        }
    }
}

@Composable
internal fun TransformToolbar(
    tool: PhotoEditTool, onTool: (PhotoEditTool) -> Unit,
    filtered: Boolean, onFilter: (Boolean) -> Unit, onAction: (String) -> Unit,
    snapping: Boolean? = null, onSnapping: (() -> Unit)? = null,
    precision: Boolean? = null, onPrecision: (() -> Unit)? = null,
) {
    var more by remember { mutableStateOf(false) }
    var interpolation by remember { mutableStateOf(false) }
    Column(horizontalAlignment=Alignment.CenterHorizontally) {
        Row(Modifier.horizontalScroll(rememberScrollState()),verticalAlignment=Alignment.CenterVertically) {
            listOf(PhotoEditTool.STRETCH to Icons.Outlined.CropFree,
                PhotoEditTool.MOVE to Icons.Outlined.AspectRatio,
                PhotoEditTool.DISTORT to Icons.Outlined.Transform,
                PhotoEditTool.WARP to Icons.Outlined.GridOn).forEach { (mode,icon) ->
                TransformIcon(mode.transformLabel(),icon,active=tool==mode) { onTool(mode) }
            }
            Box {
                TransformIcon("More tools",Icons.Outlined.MoreHoriz,
                    active=tool in listOf(PhotoEditTool.PUSH,PhotoEditTool.BULGE,PhotoEditTool.PINCH)) { more=true }
                DropdownMenu(more,{ more=false }) {
                    listOf(PhotoEditTool.PUSH to Icons.Outlined.OpenWith,
                        PhotoEditTool.BULGE to Icons.Outlined.ZoomOutMap,
                        PhotoEditTool.PINCH to Icons.Outlined.ZoomInMap).forEach { (mode,icon) ->
                        DropdownMenuItem(text={ Text(mode.transformLabel()) },leadingIcon={ Icon(icon,null) },
                            onClick={ onTool(mode);more=false })
                    }
                }
            }
        }
        HorizontalDivider(color=Color(0xFF45474B))
        Row(Modifier.horizontalScroll(rememberScrollState()),verticalAlignment=Alignment.CenterVertically) {
            if(snapping!=null && onSnapping!=null) TransformIcon("Snapping",Icons.Outlined.Bolt,snapping,onClick=onSnapping)
            TransformIcon("Flip horizontal",Icons.Outlined.Flip) { onAction("flipX") }
            TransformIcon("Flip vertical",Icons.Outlined.Flip,rotation=90f) { onAction("flipY") }
            TransformIcon("Rotate 45°",Icons.Outlined.RotateRight) { onAction("rotate") }
            TransformIcon("Fit to canvas",Icons.Outlined.FitScreen) { onAction("fit") }
            Box {
                TransformIcon(if(filtered) "Bilinear" else "Nearest neighbor",Icons.Outlined.Texture) { interpolation=true }
                DropdownMenu(interpolation,{ interpolation=false }) {
                    listOf(false to "Nearest neighbor",true to "Bilinear").forEach { (value,label) ->
                        DropdownMenuItem(text={ Text(label) },
                            trailingIcon={ if(filtered==value) Icon(Icons.Outlined.Check,null) },
                            onClick={ onFilter(value);interpolation=false })
                    }
                }
            }
            TransformIcon("Reset",Icons.Outlined.RestartAlt) { onAction("reset") }
            if(precision!=null && onPrecision!=null) TransformIcon("Exact size",Icons.Outlined.Tune,precision,onClick=onPrecision)
        }
    }
}
