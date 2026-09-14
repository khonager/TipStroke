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
    fun summariesFrontToBack(): List<LayerSummary> = layers.asReversed().map { it.summary() }
    fun allocatedTiles(): Int = layers.filterIsInstance<RasterLayerRuntime>().sumOf { it.tiles.allocatedTileCount }
    fun lastDirtyTiles(): Int = selectedRaster()?.tiles?.lastDirtyTiles?.size ?: 0
    fun undoBytes(): Long = layers.filterIsInstance<RasterLayerRuntime>().sumOf { it.tiles.history.estimatedBytes }

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

    private fun indexAboveSelected() = (layers.indexOfFirst { it.id == selectedId } + 1).coerceAtMost(layers.size)
    private fun newRaster(name: String) = RasterLayerRuntime(newId(), name, tiles = TileStore(canvasWidth, canvasHeight))
    private fun newId() = LayerId(UUID.randomUUID().toString())
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
