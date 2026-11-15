package dev.tipstroke.core.model

/** Portable authoring settings. Jetpack types belong in the platform renderer, not :core. */
enum class InkTexture { SOLID, GRAPHITE, SOFT }

data class InkBrush(
    val texture: InkTexture = InkTexture.SOLID,
    val aspectRatio: Float = 1f,
    val rounding: Float = 1f,
    val rotationDegrees: Float = 0f,
    val particles: Boolean = false,
    val accumulate: Boolean = false,
    val flow: Float = 1f,
    val tiltSize: Float = 0f,
) {
    init {
        require(aspectRatio in .1f..1f && rounding in 0f..1f && rotationDegrees in -180f..180f)
        require(flow in .01f..1f && tiltSize in 0f..3f)
        require(texture != InkTexture.SOFT || particles) { "Soft Ink tips require particle rendering." }
    }
}
