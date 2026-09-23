package dev.tipstroke.desktop

import java.awt.event.MouseEvent
import java.io.File
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopDrawingCanvasTest {
    @Test
    fun strokeExportsAndUndoRemovesItsPixels() {
        val output = File.createTempFile("tipstroke-desktop", ".png")
        val undone = File.createTempFile("tipstroke-desktop-undone", ".png")
        output.deleteOnExit()
        undone.deleteOnExit()

        lateinit var canvas: DesktopDrawingCanvas
        var history = HistoryState(false, false)
        SwingUtilities.invokeAndWait {
            canvas = DesktopDrawingCanvas().apply {
                setSize(900, 700)
                fitToView()
                onHistoryChanged = { history = it }
            }
            canvas.dispatchMouse(MouseEvent.MOUSE_PRESSED, 450, 350, MouseEvent.BUTTON1)
            canvas.dispatchMouse(MouseEvent.MOUSE_DRAGGED, 490, 375, MouseEvent.NOBUTTON)
            canvas.dispatchMouse(MouseEvent.MOUSE_RELEASED, 490, 375, MouseEvent.BUTTON1)
            assertTrue(canvas.exportPng(output))
        }

        assertTrue(history.canUndo)
        assertTrue(ImageIO.read(output).hasVisiblePixel())

        SwingUtilities.invokeAndWait {
            canvas.undo()
            assertTrue(canvas.exportPng(undone))
        }

        assertFalse(history.canUndo)
        assertTrue(history.canRedo)
        assertFalse(ImageIO.read(undone).hasVisiblePixel())
    }

    private fun DesktopDrawingCanvas.dispatchMouse(id: Int, x: Int, y: Int, button: Int) {
        val event = MouseEvent(this, id, System.currentTimeMillis(), 0, x, y, 1, false, button)
        when (id) {
            MouseEvent.MOUSE_PRESSED -> mouseListeners.forEach { it.mousePressed(event) }
            MouseEvent.MOUSE_DRAGGED -> mouseMotionListeners.forEach { it.mouseDragged(event) }
            MouseEvent.MOUSE_RELEASED -> mouseListeners.forEach { it.mouseReleased(event) }
        }
    }

    private fun java.awt.image.BufferedImage.hasVisiblePixel(): Boolean {
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (getRGB(x, y) ushr 24 != 0) return true
            }
        }
        return false
    }
}
