package dev.tipstroke.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import dev.tipstroke.core.editor.NativeBrush
import dev.tipstroke.core.model.RgbaColor
import dev.tipstroke.ui.LocalEraserPreview
import dev.tipstroke.ui.LocalStrokePreview

@Composable
fun TipStrokeTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalStrokePreview provides { tip, modifier ->
        val erase = LocalEraserPreview.current
        val sample = remember(tip, erase) { if (erase) NativeBrush(tip).eraserPreset() else tip }
        TipStrokeThumbnail(sample, modifier, erasing = erase, color = RgbaColor(1f, 1f, 1f))
    }) { dev.tipstroke.ui.TipStrokeTheme(content) }
}
