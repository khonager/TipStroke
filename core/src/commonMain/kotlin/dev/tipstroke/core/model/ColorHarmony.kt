package dev.tipstroke.core.model

/** HSV hue relationships, in degrees around the color wheel. */
enum class ColorHarmony(val label: String, private vararg val offsets: Float) {
    COMPLEMENTARY("Complementary", 0f, 180f),
    SPLIT_COMPLEMENTARY("Split complementary", 0f, 150f, 210f),
    ANALOGOUS("Analogous", 0f, 30f, 330f),
    TRIADIC("Triadic", 0f, 120f, 240f),
    TETRADIC("Tetradic", 0f, 90f, 180f, 270f);

    fun hues(baseHue: Float): List<Float> {
        require(baseHue.isFinite())
        return offsets.map { ((baseHue % 360f + it) % 360f + 360f) % 360f }
    }
}
