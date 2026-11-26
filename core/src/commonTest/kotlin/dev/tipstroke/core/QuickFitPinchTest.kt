package dev.tipstroke.core

import dev.tipstroke.core.model.QuickFitPinch
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuickFitPinchTest {
    @Test fun quickContractionFitsOnlyOnceAndOnlyOnPromptRelease() {
        val pinch = QuickFitPinch()
        pinch.begin(200f, 0)
        pinch.update(128f, 150)
        assertTrue(pinch.finish(180))
        assertFalse(pinch.finish(181))
        pinch.begin(200f, 0)
        pinch.update(128f, 150)
        assertFalse(pinch.finish(270))
        pinch.begin(200f, 0)
        pinch.update(128f, 400)
        assertFalse(pinch.finish(410))
        pinch.begin(200f, 0)
        pinch.update(100f, 100)
        pinch.update(130f, 140)
        assertFalse(pinch.finish(150))
    }
}
