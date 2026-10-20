package dev.tipstroke.app

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.snapshots.Snapshot
import androidx.lifecycle.ViewModelProvider
import dev.tipstroke.core.model.LayerKind
import dev.tipstroke.drawing.android.DrawingLibrary
import dev.tipstroke.drawing.android.DrawingSurface
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainActivityRestorationTest {
    @Test fun newDrawingAndImageSurviveActivityRecreationAndFreshActivityStateRestore() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        val library = DrawingLibrary(activity)
        val id = library.newId()
        ViewModelProvider(activity)[TipStrokeAppState::class.java].screen =
            AppScreen.Editor(id, "Lifecycle test", 128, 128, false)
        controller.start().resume().visible()
        val original = awaitSurface(activity)
        original.setCanvasBackgroundColor(Color.YELLOW)
        val source = File(activity.cacheDir, "activity-image.png")
        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(Color.BLUE)
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        original.addImage(Uri.fromFile(source)).getOrThrow()
        original.setSelectedImageScale(2f)
        // Publish the updated save callback before exercising real activity lifecycle methods.
        shadowOf(activity.mainLooper).idleFor(Duration.ofMillis(32))

        controller.recreate()
        val recreated = awaitSurface(controller.get())
        assertNotSame(original, recreated)
        assertArtwork(recreated)

        val state = Bundle()
        controller.pause().stop().saveInstanceState(state).destroy()
        val restoredController = Robolectric.buildActivity(MainActivity::class.java)
            .create(state).start().resume().visible()
        try {
            val restored = awaitSurface(restoredController.get())
            assertArtwork(restored)
            val route = ViewModelProvider(restoredController.get())[TipStrokeAppState::class.java].screen
            assertEquals(id, (route as AppScreen.Editor).id)
            assertTrue(route.existing)
        } finally {
            restoredController.pause().stop().destroy()
            source.delete()
        }
    }

    private fun assertArtwork(surface: DrawingSurface) {
        var images = 0
        var paper = 0
        surface.layersListener = { layers, _, _, _, background, _ ->
            images = layers.count { it.kind == LayerKind.IMAGE && it.imageScale == 2f }
            paper = background
        }
        surface.publishLayers()
        assertEquals(128, surface.canvasWidthPx)
        assertEquals(1, images)
        assertEquals(Color.YELLOW, paper)
    }

    private fun awaitSurface(activity: MainActivity): DrawingSurface {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline) {
            // Drive snapshot notifications as well as frames with Robolectric's paused looper.
            Snapshot.sendApplyNotifications()
            val root = activity.window.decorView
            root.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 800, 1600)
            shadowOf(activity.mainLooper).idleFor(Duration.ofMillis(32))
            findSurface(root)?.takeIf { it.isEnabled }?.let { return it }
            Thread.sleep(10)
        }
        val surface = findSurface(activity.window.decorView)
        val logs = File(activity.filesDir, "diagnostics/events.log").takeIf { it.exists() }?.readText()
        error("Editor did not finish opening: surface=$surface enabled=${surface?.isEnabled} " +
            "screen=${ViewModelProvider(activity)[TipStrokeAppState::class.java].screen}\n$logs")
    }

    private fun findSurface(view: View): DrawingSurface? {
        if (view is DrawingSurface) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findSurface(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
