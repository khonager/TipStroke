package dev.tipstroke.core.model

enum class FingerAction(val displayName: String) {
    DRAW("Draw"),
    NAVIGATE("Navigate canvas"),
    SMUDGE("Smudge"),
    PICK_COLOR("Pick color"),
    UNDO("Undo"),
    REDO("Redo"),
    DISABLED("Disabled"),
}

enum class StylusButtonAction(val displayName: String) {
    TOGGLE_ERASER("Switch brush / eraser"),
    UNDO("Undo"),
    REDO("Redo"),
    DISABLED("Disabled"),
}

data class GestureSettings(
    val oneFingerDrag: FingerAction = FingerAction.NAVIGATE,
    val oneFingerHold: FingerAction = FingerAction.PICK_COLOR,
    val twoFingerTap: FingerAction = FingerAction.UNDO,
    val threeFingerTap: FingerAction = FingerAction.REDO,
    val holdDelayMillis: Long = 420L,
    val smudgeStrength: Float = .45f,
    val rotationLocked: Boolean = false,
    val hideNavigationBarWhileDrawing: Boolean = true,
    val showStylusHoverPreview: Boolean = true,
    val stylusPrimaryButton: StylusButtonAction = StylusButtonAction.TOGGLE_ERASER,
    val stylusSecondaryButton: StylusButtonAction = StylusButtonAction.UNDO,
    val fillAnimationMillis: Long = 2500L,
    val imageSnapping: Boolean = false,
    val imageSnapAngle: Int = 15,
    val imageSnapStrength: Float = .5f,
) {
    init {
        require(holdDelayMillis in 150L..1500L)
        require(fillAnimationMillis in 250L..5000L)
        require(smudgeStrength in 0f..1f)
        require(imageSnapAngle in listOf(5, 15, 30, 45, 90))
        require(imageSnapStrength in 0f..1f)
    }
}
