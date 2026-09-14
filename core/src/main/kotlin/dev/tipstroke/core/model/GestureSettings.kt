package dev.tipstroke.core.model

enum class FingerAction(val displayName: String) {
    NAVIGATE("Navigate canvas"),
    SMUDGE("Smudge"),
    PICK_COLOR("Pick color"),
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
) {
    init {
        require(holdDelayMillis in 150L..1500L)
        require(smudgeStrength in 0f..1f)
    }
}
