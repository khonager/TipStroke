package dev.tipstroke.drawing.android

import android.os.Build
import androidx.annotation.Keep

/** The same nearest-centerline coverage calculation as the Kotlin fallback. No artwork ownership. */
@Keep
internal object NativeEraser {
    val available = !Build.FINGERPRINT.contains("robolectric", true) && runCatching {
        System.loadLibrary("tipstroke_pixels")
    }.onFailure { android.util.Log.w("TipStroke", "Native eraser unavailable; using raster fallback", it) }.isSuccess

    // Load before input, alongside Ink's native initialization.
    fun warmUp() { available }

    external fun applySegment(pixels: IntArray, originals: IntArray, alpha: ByteArray, distances: FloatArray,
        tileSize: Int, x0: Int, x1: Int, y0: Int, y1: Int,
        ax: Float, ay: Float, dx: Float, dy: Float, inverseLengthSquared: Float,
        sizeA: Float, sizeB: Float, alphaA: Float, alphaB: Float, hardness: Float): Boolean
}
