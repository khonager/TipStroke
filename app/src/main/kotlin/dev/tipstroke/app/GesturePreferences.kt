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
        fillAnimationMillis = preferences.getLong("fill_animation_ms", GestureSettings().fillAnimationMillis).coerceIn(250L, 5000L),
        smudgeStrength = preferences.getFloat("smudge_strength", .45f).coerceIn(0f, 1f),
        imageSnapping = preferences.getBoolean("image_snapping", false),
        imageSnapAngle = preferences.getInt("image_snap_angle", 15).takeIf { it in listOf(5, 15, 30, 45, 90) } ?: 15,
        imageSnapStrength = preferences.getFloat("image_snap_strength", .5f).takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: .5f,
        rotationLocked = preferences.getBoolean("rotation_locked", false),
        hideNavigationBarWhileDrawing = preferences.getBoolean("hide_navigation_bar", true),
        showStylusHoverPreview = preferences.getBoolean("show_stylus_hover_preview", true),
        stylusPrimaryButton = stylusAction("stylus_primary", StylusButtonAction.TOGGLE_ERASER),
        stylusSecondaryButton = stylusAction("stylus_secondary", StylusButtonAction.UNDO),
    )

    fun save(settings: GestureSettings) {
        preferences.edit()
            .putString("one_drag", settings.oneFingerDrag.name)
            .putString("one_hold", settings.oneFingerHold.name)
            .putString("two_tap", settings.twoFingerTap.name)
            .putString("three_tap", settings.threeFingerTap.name)
            .putLong("hold_delay", settings.holdDelayMillis)
            .putLong("fill_animation_ms", settings.fillAnimationMillis)
            .putFloat("smudge_strength", settings.smudgeStrength)
            .putBoolean("image_snapping", settings.imageSnapping)
            .putInt("image_snap_angle", settings.imageSnapAngle)
            .putFloat("image_snap_strength", settings.imageSnapStrength)
            .putBoolean("rotation_locked", settings.rotationLocked)
            .putBoolean("hide_navigation_bar", settings.hideNavigationBarWhileDrawing)
            .putBoolean("show_stylus_hover_preview", settings.showStylusHoverPreview)
            .putString("stylus_primary", settings.stylusPrimaryButton.name)
            .putString("stylus_secondary", settings.stylusSecondaryButton.name)
            .apply()
    }

    private fun action(key: String, fallback: FingerAction) =
        runCatching { FingerAction.valueOf(preferences.getString(key, fallback.name) ?: fallback.name) }.getOrDefault(fallback)

    private fun stylusAction(key: String, fallback: StylusButtonAction) =
        runCatching { StylusButtonAction.valueOf(preferences.getString(key, fallback.name) ?: fallback.name) }.getOrDefault(fallback)
}
