package dev.tipstroke.app

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

/** Action-only counterpart to text buttons. Labels remain accessible and appear on hover/hold. */
@Composable
internal fun ActionIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    label: String,
    icon: ImageVector,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        CompositionLocalProvider(LocalContentColor provides colors.contentColor) {
            TipAction(label, icon, enabled, onClick)
        }
    }
}
