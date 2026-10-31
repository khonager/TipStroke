package dev.tipstroke.app

import android.graphics.*
import dev.tipstroke.core.model.BrushTexture
import java.io.InputStream

internal object BrushSourceImporter {
    fun read(input: InputStream): BrushTexture = input.use {
        val bytes = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = it.read(buffer)
            if (count < 0) break
            require(bytes.size() + count <= 16 * 1024 * 1024) { "Choose an image smaller than 16 MiB." }
            bytes.write(buffer, 0, count)
        }
        val encoded = bytes.toByteArray()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(encoded, 0, encoded.size, bounds)
        require(bounds.outWidth in 1..16384 && bounds.outHeight in 1..16384) { "Unsupported image dimensions." }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 512) sample *= 2
        val source = requireNotNull(BitmapFactory.decodeByteArray(encoded, 0, encoded.size,
            BitmapFactory.Options().apply { inSampleSize = sample })) { "Could not read this image." }
        val output = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        try {
            val scale = 256f / maxOf(source.width, source.height)
            val w = source.width * scale; val h = source.height * scale
            Canvas(output).drawBitmap(source, null, RectF((256 - w) / 2, (256 - h) / 2, (256 + w) / 2, (256 + h) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
            val sourcePixels = IntArray(source.width * source.height)
            source.getPixels(sourcePixels, 0, source.width, 0, 0, source.width, source.height)
            val alphaSource = sourcePixels.any { Color.alpha(it) < 255 }
            val pixels = IntArray(256 * 256)
            output.getPixels(pixels, 0, 256, 0, 0, 256, 256)
            BrushTexture(256, 256, ByteArray(pixels.size) { index ->
                val pixel = pixels[index]
                (if (alphaSource) Color.alpha(pixel) else
                    ((Color.red(pixel) * 54 + Color.green(pixel) * 183 + Color.blue(pixel) * 19) / 256) * Color.alpha(pixel) / 255).toByte()
            })
        } finally { source.recycle(); output.recycle() }
    }
}
