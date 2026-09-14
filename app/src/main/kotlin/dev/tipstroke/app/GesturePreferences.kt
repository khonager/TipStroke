package dev.tipstroke.app

import android.content.Context
import dev.tipstroke.core.model.*

class GesturePreferences(context: Context) {
    private val preferences = context.getSharedPreferences("gesture-settings", Context.MODE_PRIVATE)

    fun load() = GestureSettings(
        oneFingerDrag = action("one_drag", FingerAction.NAVIGATE),
        oneFingerHold = action("one_hold", FingerAction.PICK_COLOR),
        twoFingerTap = action("two_tap", FingerAction.UNDO),
        threeFingerTap = action("three_tap", FingerAction.REDO),
        holdDelayMillis = preferences.getLong("hold_delay", 420L).coerceIn(150L, 1500L),
        smudgeStrength = preferences.getFloat("smudge_strength", .45f).coerceIn(0f, 1f),
        rotationLocked = preferences.getBoolean("rotation_locked", false),
    )

    fun save(settings: GestureSettings) {
        preferences.edit()
            .putString("one_drag", settings.oneFingerDrag.name)
            .putString("one_hold", settings.oneFingerHold.name)
            .putString("two_tap", settings.twoFingerTap.name)
            .putString("three_tap", settings.threeFingerTap.name)
            .putLong("hold_delay", settings.holdDelayMillis)
            .putFloat("smudge_strength", settings.smudgeStrength)
            .putBoolean("rotation_locked", settings.rotationLocked)
            .apply()
    }

    private fun action(key: String, fallback: FingerAction) =
        runCatching { FingerAction.valueOf(preferences.getString(key, fallback.name) ?: fallback.name) }.getOrDefault(fallback)
}
