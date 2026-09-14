package dev.tipstroke.app

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Colors = darkColorScheme(
    primary = Color(0xFFED6A5A), background = Color(0xFF17181B), surface = Color(0xFF202125),
    onPrimary = Color.White, onBackground = Color(0xFFF4F4F2), onSurface = Color(0xFFF4F4F2),
    outline = Color(0xFF45474D), surfaceVariant = Color(0xFF2A2C31), onSurfaceVariant = Color(0xFFC3C5CA),
)

@Composable fun TipStrokeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, typography = Typography(), content = content)
}
