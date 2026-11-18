package dev.tipstroke.core.canvas

import kotlin.test.*

class CanvasPresetCodecTest {
    @Test fun localPresetsRoundTripAndRespectHostLimits() {
        val presets = listOf(CanvasPreset("custom", "Paper \"A\"\n", 4096, 2048, "Saved"),
            CanvasPreset("large", "Large", 8192, 8192, "Saved"))
        val encoded = CanvasPresetCodec.encode(presets)
        assertEquals(presets, CanvasPresetCodec.decode(encoded, 8192))
        assertEquals(presets.take(1), CanvasPresetCodec.decode(encoded, 4096))
        assertEquals(emptyList(), CanvasPresetCodec.decode("broken", 4096))
    }
}
