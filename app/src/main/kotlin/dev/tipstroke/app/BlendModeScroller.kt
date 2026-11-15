package dev.tipstroke.app

import androidx.compose.runtime.Composable
import dev.tipstroke.core.model.LayerBlendMode
import dev.tipstroke.core.model.LayerId

/** Platform call site retained while the implementation is shared with desktop/iOS. */
@Composable
internal fun BlendModeScroller(
    layerId: LayerId,
    mode: LayerBlendMode,
    onPreview: (LayerBlendMode) -> Unit,
    onStart: () -> Unit,
    onFinish: () -> Unit,
) = dev.tipstroke.ui.BlendModeScroller(layerId, mode, onPreview, onStart, onFinish)
