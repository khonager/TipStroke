package dev.tipstroke.drawing.android

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import dev.tipstroke.core.model.*
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

internal sealed class CanvasLayerRuntime {
    abstract val id: LayerId
    abstract var name: String
    abstract var visible: Boolean
    abstract var opacity: Float
    abstract fun summary(): LayerSummary
}

internal class RasterLayerRuntime(
    override val id: LayerId,
    override var name: String,
    override var visible: Boolean = true,
    override var opacity: Float = 1f,
    val tiles: TileStore,
) : CanvasLayerRuntime() {
    override fun summary() = LayerSummary(id, name, LayerKind.RASTER, visible, opacity)
}

internal class ImageLayerRuntime(
    override val id: LayerId,
    override var name: String,
    override var visible: Boolean = true,
    override var opacity: Float = 1f,
    val source: OriginalImageSource,
    var transform: ImageTransform,
    /** White pixels mark erased source pixels; the original image is never rewritten. */
    val mask: TileStore = TileStore(source.width, source.height),
) : CanvasLayerRuntime() {
    override fun summary() = LayerSummary(
        id, name, LayerKind.IMAGE, visible, opacity,
        source.width, source.height, transform.scale,
    )
}

internal class LayerStack(
    private val resolver: ContentResolver,
    val canvasWidth: Int,
    val canvasHeight: Int,
    private val invalidate: () -> Unit,
) {
    /** Back-to-front painter's order. */
    val layers = mutableListOf<CanvasLayerRuntime>()
    var selectedId: LayerId
        private set

    init {
        val initial = newRaster("Paint 1")
        layers += initial
        selectedId = initial.id
    }

    fun selected(): CanvasLayerRuntime = layers.first { it.id == selectedId }
    fun selectedRaster(): RasterLayerRuntime? = selected() as? RasterLayerRuntime
    fun selectedImage(): ImageLayerRuntime? = selected() as? ImageLayerRuntime
    fun summariesFrontToBack(): List<LayerSummary> = layers.asReversed().map { it.summary() }
    fun allocatedTiles(): Int = layers.sumOf { layer ->
        when (layer) {
            is RasterLayerRuntime -> layer.tiles.allocatedTileCount
            is ImageLayerRuntime -> layer.mask.allocatedTileCount
        }
    }
    fun lastDirtyTiles(): Int = selectedRaster()?.tiles?.lastDirtyTiles?.size ?: 0
    fun undoBytes(): Long = layers.sumOf { layer ->
        when (layer) {
            is RasterLayerRuntime -> layer.tiles.history.estimatedBytes
            is ImageLayerRuntime -> layer.mask.history.estimatedBytes
        }
    }

    fun selectedStore(): TileStore? = when (val layer = selected()) {
        is RasterLayerRuntime -> layer.tiles
        is ImageLayerRuntime -> layer.mask
    }

    fun frequentColors(limit: Int = 4): List<RgbaColor> {
        val combined = mutableMapOf<Int, Long>()
        layers.filterIsInstance<RasterLayerRuntime>().forEach { layer ->
            layer.tiles.snapshotColorUsage().forEach { (argb, weight) ->
                combined[argb] = combined.getOrDefault(argb, 0L) + weight
            }
        }
        return combined.entries.sortedByDescending { it.value }.take(limit).map { (argb) ->
            RgbaColor(
                android.graphics.Color.red(argb) / 255f,
                android.graphics.Color.green(argb) / 255f,
                android.graphics.Color.blue(argb) / 255f,
                android.graphics.Color.alpha(argb) / 255f,
            )
        }
    }

    fun addRaster(): LayerId {
        val layer = newRaster("Paint ${layers.count { it is RasterLayerRuntime } + 1}")
        layers.add(indexAboveSelected(), layer)
        selectedId = layer.id
        invalidate()
        return layer.id
    }

    fun addImage(uri: Uri): Result<LayerId> = runCatching {
        val source = OriginalImageSource(resolver, uri, invalidate)
        val fit = minOf(canvasWidth.toFloat() / source.width, canvasHeight.toFloat() / source.height)
        val layer = ImageLayerRuntime(
            id = newId(),
            name = resolver.displayName(uri) ?: "Imported image",
            source = source,
            transform = ImageTransform(canvasWidth / 2f, canvasHeight / 2f, fit.coerceAtMost(1f)),
        )
        layers.add(indexAboveSelected(), layer)
        selectedId = layer.id
        invalidate()
        layer.id
    }

    fun select(id: LayerId) { if (layers.any { it.id == id }) { selectedId = id; invalidate() } }

    fun setOpacity(value: Float) {
        selected().opacity = value.coerceIn(0f, 1f)
        invalidate()
    }

    fun toggleVisible(id: LayerId) {
        layers.firstOrNull { it.id == id }?.let { it.visible = !it.visible; invalidate() }
    }

    fun setImageScale(value: Float) {
        (selected() as? ImageLayerRuntime)?.let {
            it.transform = it.transform.copy(scale = value.coerceIn(.02f, 4f))
            invalidate()
        }
    }

    fun transformSelectedImage(deltaX: Float, deltaY: Float, scaleFactor: Float = 1f, rotationDeltaDegrees: Float = 0f) {
        selectedImage()?.let { image ->
            image.transform = image.transform.changedBy(deltaX, deltaY, scaleFactor, rotationDeltaDegrees)
            invalidate()
        }
    }

    fun fitSelectedImage() {
        (selected() as? ImageLayerRuntime)?.let {
            val scale = minOf(canvasWidth.toFloat() / it.source.width, canvasHeight.toFloat() / it.source.height)
            it.transform = it.transform.copy(centerX = canvasWidth / 2f, centerY = canvasHeight / 2f, scale = scale)
            invalidate()
        }
    }

    fun originalSizeSelectedImage() {
        (selected() as? ImageLayerRuntime)?.let {
            it.transform = it.transform.copy(centerX = canvasWidth / 2f, centerY = canvasHeight / 2f, scale = 1f)
            invalidate()
        }
    }

    fun moveSelected(towardFront: Boolean) {
        val index = layers.indexOfFirst { it.id == selectedId }
        val destination = (index + if (towardFront) 1 else -1).coerceIn(0, layers.lastIndex)
        if (index != destination) {
            val layer = layers.removeAt(index)
            layers.add(destination, layer)
            invalidate()
        }
    }

    fun deleteSelected(): Boolean {
        if (layers.size == 1) return false
        val index = layers.indexOfFirst { it.id == selectedId }
        val removed = layers.removeAt(index)
        if (removed is ImageLayerRuntime) removed.source.close()
        selectedId = layers[index.coerceAtMost(layers.lastIndex)].id
        invalidate()
        return true
    }

    fun snapshot(): DrawingSnapshot = DrawingSnapshot(
        canvasWidth, canvasHeight, selectedId,
        layers.map { layer ->
            when (layer) {
                is RasterLayerRuntime -> SavedRasterSnapshot(
                    layer.id, layer.name, layer.visible, layer.opacity,
                    layer.tiles.snapshotTiles(), layer.tiles.snapshotColorUsage(),
                )
                is ImageLayerRuntime -> SavedImageSnapshot(
                    layer.id, layer.name, layer.visible, layer.opacity, layer.source.uri,
                    layer.source.width, layer.source.height, layer.transform, layer.mask.snapshotTiles(),
                )
            }
        },
    )

    fun replaceWith(loaded: LoadedProject) {
        layers.filterIsInstance<ImageLayerRuntime>().forEach { it.source.close() }
        layers.clear()
        loaded.layers.forEach { layer ->
            layers += when (layer) {
                is LoadedRaster -> RasterLayerRuntime(
                    layer.id, layer.name, layer.visible, layer.opacity,
                    TileStore(loaded.widthPx, loaded.heightPx).apply {
                        replaceTiles(layer.tiles)
                        replaceColorUsage(layer.colorUsage)
                    },
                )
                is LoadedImage -> ImageLayerRuntime(
                    layer.id, layer.name, layer.visible, layer.opacity,
                    OriginalImageSource(resolver, Uri.fromFile(layer.assetFile), invalidate), layer.transform,
                    mask = TileStore(layer.originalWidthPx, layer.originalHeightPx).apply { replaceTiles(layer.maskTiles) },
                )
            }
        }
        selectedId = loaded.selectedId
        invalidate()
    }

    fun colorAt(x: Float, y: Float): RgbaColor {
        var result = android.graphics.Color.WHITE
        layers.forEach { layer ->
            if (!layer.visible || layer.opacity <= 0f) return@forEach
            val source = when (layer) {
                is RasterLayerRuntime -> layer.tiles.colorAt(x.roundToInt(), y.roundToInt())
                is ImageLayerRuntime -> {
                    val bitmap = layer.source.bitmapOrRequest() ?: return@forEach
                    val radians = Math.toRadians((-layer.transform.rotationDegrees).toDouble())
                    val dx = x - layer.transform.centerX
                    val dy = y - layer.transform.centerY
                    val unrotatedX = (dx * cos(radians) - dy * sin(radians)).toFloat()
                    val unrotatedY = (dx * sin(radians) + dy * cos(radians)).toFloat()
                    val imageX = (unrotatedX / layer.transform.scale + layer.source.width / 2f).toInt()
                    val imageY = (unrotatedY / layer.transform.scale + layer.source.height / 2f).toInt()
                    if (imageX in 0 until bitmap.width && imageY in 0 until bitmap.height &&
                        android.graphics.Color.alpha(layer.mask.colorAt(imageX, imageY)) == 0
                    ) bitmap.getPixel(imageX, imageY) else android.graphics.Color.TRANSPARENT
                }
            }
            result = sourceOver(source, result, layer.opacity)
        }
        return RgbaColor(
            android.graphics.Color.red(result) / 255f,
            android.graphics.Color.green(result) / 255f,
            android.graphics.Color.blue(result) / 255f,
            android.graphics.Color.alpha(result) / 255f,
        )
    }

    private fun indexAboveSelected() = (layers.indexOfFirst { it.id == selectedId } + 1).coerceAtMost(layers.size)
    private fun newRaster(name: String) = RasterLayerRuntime(newId(), name, tiles = TileStore(canvasWidth, canvasHeight))
    private fun newId() = LayerId(UUID.randomUUID().toString())

    fun imagePointFromDocument(image: ImageLayerRuntime, point: android.graphics.PointF): android.graphics.PointF {
        val radians = Math.toRadians((-image.transform.rotationDegrees).toDouble())
        val dx = point.x - image.transform.centerX
        val dy = point.y - image.transform.centerY
        val x = (dx * cos(radians) - dy * sin(radians)).toFloat() / image.transform.scale + image.source.width / 2f
        val y = (dx * sin(radians) + dy * cos(radians)).toFloat() / image.transform.scale + image.source.height / 2f
        return android.graphics.PointF(x, y)
    }

    private fun sourceOver(source: Int, destination: Int, layerOpacity: Float): Int {
        val sourceAlpha = android.graphics.Color.alpha(source) / 255f * layerOpacity
        if (sourceAlpha <= 0f) return destination
        val destinationAlpha = android.graphics.Color.alpha(destination) / 255f
        val outAlpha = sourceAlpha + destinationAlpha * (1f - sourceAlpha)
        fun channel(sourceChannel: Int, destinationChannel: Int): Int =
            ((sourceChannel * sourceAlpha + destinationChannel * destinationAlpha * (1f - sourceAlpha)) / outAlpha).roundToInt().coerceIn(0, 255)
        return android.graphics.Color.argb(
            (outAlpha * 255).roundToInt(),
            channel(android.graphics.Color.red(source), android.graphics.Color.red(destination)),
            channel(android.graphics.Color.green(source), android.graphics.Color.green(destination)),
            channel(android.graphics.Color.blue(source), android.graphics.Color.blue(destination)),
        )
    }
}

/** Keeps the original URI as authority and treats decoded bitmaps as disposable render caches. */
internal class OriginalImageSource(
    private val resolver: ContentResolver,
    val uri: Uri,
    private val invalidate: () -> Unit,
) {
    val width: Int
    val height: Int
    @Volatile private var bitmap: Bitmap? = null
    @Volatile private var loading = false
    @Volatile private var closed = false

    init {
        val dimensions = probeDimensions(resolver, uri)
        width = dimensions.first
        height = dimensions.second
    }

    fun bitmapOrRequest(): Bitmap? {
        bitmap?.let { return it }
        if (!loading) {
            loading = true
            decodeExecutor.execute {
                val decoded = runCatching {
                    ImageDecoder.decodeBitmap(decoderSource(resolver, uri)) { decoder, _, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                }.getOrNull()
                mainHandler.post {
                    if (closed) decoded?.recycle() else bitmap = decoded
                    loading = false
                    if (!closed) invalidate()
                }
            }
        }
        return null
    }

    fun close() { closed = true; bitmap?.recycle(); bitmap = null }

    companion object {
        private val decodeExecutor = Executors.newSingleThreadExecutor { task -> Thread(task, "TipStroke-image-decode").apply { isDaemon = true } }
        private val mainHandler = Handler(Looper.getMainLooper())

        private fun probeDimensions(resolver: ContentResolver, uri: Uri): Pair<Int, Int> {
            var result: Pair<Int, Int>? = null
            val preview = ImageDecoder.decodeBitmap(decoderSource(resolver, uri)) { decoder, info, _ ->
                result = info.size.width to info.size.height
                decoder.setTargetSize(1, 1)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            preview.recycle()
            return requireNotNull(result) { "The selected file is not a supported image" }.also {
                require(it.first > 0 && it.second > 0) { "The selected image has invalid dimensions" }
            }
        }

        private fun decoderSource(resolver: ContentResolver, uri: Uri): ImageDecoder.Source =
            if (uri.scheme == ContentResolver.SCHEME_FILE) ImageDecoder.createSource(File(requireNotNull(uri.path)))
            else ImageDecoder.createSource(resolver, uri)
    }
}

private fun ContentResolver.displayName(uri: Uri): String? = runCatching {
    query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
}.getOrNull()
