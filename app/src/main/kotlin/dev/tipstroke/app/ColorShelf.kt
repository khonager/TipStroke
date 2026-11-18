package dev.tipstroke.app

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.staticCompositionLocalOf

internal class ColorShelf private constructor(prefs: SharedPreferences) : dev.tipstroke.ui.ColorShelf(
    prefs.getString("saved", "").orEmpty(), prefs.getInt("slots", 4), prefs.getBoolean("recent", false),
    { saved, slots, recent -> prefs.edit().putString("saved", saved).putInt("slots", slots).putBoolean("recent", recent).apply() },
) {
    constructor(context: Context) : this(context.getSharedPreferences("color-shelf", Context.MODE_PRIVATE))
}
internal val LocalColorShelf = staticCompositionLocalOf<ColorShelf?> { null }
