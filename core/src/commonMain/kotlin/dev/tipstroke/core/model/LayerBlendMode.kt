package dev.tipstroke.core.model

/** Stable serialized names; independent of platform graphics APIs. */
enum class LayerBlendMode(val label: String, val family: String) {
    NORMAL("Normal", "Normal"),
    MULTIPLY("Multiply", "Darken"), DARKEN("Darken", "Darken"), COLOR_BURN("Color Burn", "Darken"),
    LIGHTEN("Lighten", "Lighten"), SCREEN("Screen", "Lighten"), COLOR_DODGE("Color Dodge", "Lighten"), ADD("Add", "Lighten"),
    OVERLAY("Overlay", "Contrast"), SOFT_LIGHT("Soft Light", "Contrast"), HARD_LIGHT("Hard Light", "Contrast"),
    DIFFERENCE("Difference", "Difference"), EXCLUSION("Exclusion", "Difference"),
    HUE("Hue", "Color"), SATURATION("Saturation", "Color"), COLOR("Color", "Color"), LUMINOSITY("Luminosity", "Color");

    companion object {
        fun fromStorage(value: String): LayerBlendMode = entries.firstOrNull { it.name == value } ?: NORMAL
    }
}
