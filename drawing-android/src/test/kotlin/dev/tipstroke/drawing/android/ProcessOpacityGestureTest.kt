package dev.tipstroke.drawing.android

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.util.zip.GZIPInputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProcessOpacityGestureTest {
    private fun recorder(surface: DrawingSurface): ProcessRecorder =
        DrawingSurface::class.java.getDeclaredField("processRecorder").apply { isAccessible = true }
            .get(surface) as ProcessRecorder

    private fun lastMetadata(recorder: ProcessRecorder): JSONObject {
        val action = recorder.snapshot().actions.last()
        return DataInputStream(GZIPInputStream(ByteArrayInputStream(action))).use { input ->
            assertEquals(0, input.readUnsignedByte())
            JSONObject(input.readUTF())
        }
    }

    @Test fun layerAndBackgroundOpacityEachRecordOnlyOnRelease() {
        val surface = DrawingSurface(RuntimeEnvironment.getApplication()).apply { configureBlank(64, 64) }
        val recorder = recorder(surface)
        val initialActions = recorder.snapshot().actionCount

        surface.beginOpacityAdjustment()
        repeat(20) { surface.setSelectedLayerOpacity(1f - (it + 1) * .03f) }
        assertEquals(initialActions, recorder.snapshot().actionCount)
        surface.finishOpacityAdjustment()
        assertEquals(initialActions + 1, recorder.snapshot().actionCount)
        assertEquals(.4, lastMetadata(recorder).getJSONArray("layers").getJSONObject(0).getDouble("opacity"), .0001)

        surface.beginOpacityAdjustment()
        repeat(20) { surface.setCanvasBackgroundOpacity(1f - (it + 1) * .04f) }
        assertEquals(initialActions + 1, recorder.snapshot().actionCount)
        surface.finishOpacityAdjustment()
        assertEquals(initialActions + 2, recorder.snapshot().actionCount)
        assertEquals(.2, lastMetadata(recorder).getDouble("opacity"), .0001)
    }
}
