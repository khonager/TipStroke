package dev.tipstroke.app

import android.content.Context

/** Per-document local editor chrome; never changes the artwork or project schema. */
internal class DrawingUiPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("drawing-ui", Context.MODE_PRIVATE)
    var useFilesForImages: Boolean
        get() = preferences.getBoolean("use-files-for-images", false)
        set(value) { preferences.edit().putBoolean("use-files-for-images", value).apply() }

    var holdToolPicker: Boolean
        get() = preferences.getBoolean("hold-tool-picker", true)
        set(value) { preferences.edit().putBoolean("hold-tool-picker", value).apply() }

    fun animationOpen(documentId: String?): Boolean =
        documentId?.let { preferences.getBoolean("animation:$it", true) } ?: true
    fun saveAnimationOpen(documentId: String?, open: Boolean) {
        if (documentId != null) preferences.edit().putBoolean("animation:$documentId", open).apply()
    }
}
