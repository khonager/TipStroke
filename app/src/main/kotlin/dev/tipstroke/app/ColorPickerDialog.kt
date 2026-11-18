package dev.tipstroke.app

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.tipstroke.core.model.RgbaColor

internal typealias ColorPickerMode = dev.tipstroke.ui.ColorPickerMode

@Composable
internal fun ColorPickerPanel(
    initialColor: RgbaColor,
    mode: ColorPickerMode,
    drawingPalette: List<RgbaColor>,
    paletteColorCount: Int,
    originalImageColors: Boolean = false,
    colorHistory: List<RgbaColor>,
    onColorSelected: (RgbaColor) -> Unit,
    onModeChanged: (ColorPickerMode) -> Unit,
    onPaletteColorCountChanged: (Int) -> Unit,
    onOriginalImageColorsChanged: (Boolean) -> Unit = {},
    onClearHistory: () -> Unit,
    compact: Boolean = false,
    title: String = "Colors",
    showOriginalImageColors: Boolean = true,
    showPaletteAndHistory: Boolean = true,
    footer: @Composable ColumnScope.() -> Unit = {},
    modifier: Modifier = Modifier,
) {
    dev.tipstroke.ui.ColorPickerPanel(
        initialColor, mode, drawingPalette, paletteColorCount, originalImageColors, colorHistory,
        onColorSelected, onModeChanged, onPaletteColorCountChanged, onOriginalImageColorsChanged,
        onClearHistory, compact, title, showOriginalImageColors, showPaletteAndHistory,
        shelf = { selected, choose -> LocalColorShelf.current?.let { ColorShelfEditor(it, selected, choose) } },
        footer = footer, modifier = modifier,
    )
}

internal fun squareToDisc(x: Float, y: Float) = dev.tipstroke.ui.squareToDisc(x, y)
internal fun discToSquare(x: Float, y: Float) = dev.tipstroke.ui.discToSquare(x, y)
internal fun colorToHex(color: RgbaColor) = dev.tipstroke.ui.colorToHex(color)
internal fun parseHexColor(input: String) = dev.tipstroke.ui.parseHexColor(input)
