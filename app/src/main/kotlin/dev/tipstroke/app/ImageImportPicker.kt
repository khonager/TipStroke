package dev.tipstroke.app

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
internal fun rememberImageImportPicker(onResult: (Uri?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val preferences = remember(context) { DrawingUiPreferences(context) }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia(), onResult)
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), onResult)
    return {
        if (preferences.useFilesForImages) files.launch(arrayOf("image/*"))
        else photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
}
