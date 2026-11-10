package dev.tipstroke.app

import android.content.Context

/** Per-document local editor chrome; never changes the artwork or project schema. */
internal class DrawingUiPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("drawing-ui", Context.MODE_PRIVATE)
    fun animationOpen(documentId: String?): Boolean =
        documentId?.let { preferences.getBoolean("animation:$it", true) } ?: true
    fun saveAnimationOpen(documentId: String?, open: Boolean) {
        if (documentId != null) preferences.edit().putBoolean("animation:$documentId", open).apply()
    }
}
