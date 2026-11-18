package dev.tipstroke.core.model

import kotlin.math.*

/** Canvas-anchored patterns remain registered across separate strokes and sparse tiles. */
object MangaTips {
    private fun tip(id: String, name: String, pattern: (Int, Int) -> Boolean): BrushPreset = BrushPreset.Blank.copy(
        id = BrushId("manga-$id-v1"), displayName = name, baseSizePx = 96f, hardness = 1f,
        spacing = .05f, opacity = 1f, pressureToSize = PressureCurve(.5f,1f),
        pressureToOpacity = PressureCurve(1f,1f),
        stamp = StampBrush(grain = StampGrain.IMAGE,
            grainTexture = BrushTexture(16,16,ByteArray(256) { if (pattern(it%16,it/16)) 255.toByte() else 0 }),
            grainPlacement = GrainPlacement.CANVAS, grainScale = 16f, grainDepth = 1f, flow = 1f),
    )
    val presets: List<BrushPreset> by lazy {
        listOf(10,30,50).map { percent ->
            val radius = sqrt(percent / 100f * 256f / PI.toFloat())
            tip("dots-$percent", "Manga dots $percent%") { x,y -> hypot(x+.5f-8,y+.5f-8) <= radius }
        } + listOf(
            tip("hatch", "Manga hatching") { x,y -> (x+y)%8 < 2 },
            tip("crosshatch", "Manga crosshatching") { x,y -> (x+y)%8 < 2 || (x-y+16)%8 < 2 },
            tip("lines", "Manga parallel shade") { _,y -> y%8 < 2 },
        )
    }
}
