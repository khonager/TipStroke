package dev.tipstroke.drawing.android

import android.content.ContentResolver
import android.content.Context
import android.graphics.*
import android.net.Uri
import android.os.Handler
import android.os.Looper
import dev.tipstroke.core.geometry.TileCoordinate
import dev.tipstroke.core.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.roundToInt

data class DrawingSummary(
    val id: String,
    val name: String,
    val widthPx: Int,
    val heightPx: Int,
    val modifiedAtMillis: Long,
    val thumbnailFile: File,
)

class DrawingLibrary(context: Context) {
    val root: File = File(context.filesDir, "drawings").apply { mkdirs() }

    fun newId(): String = UUID.randomUUID().toString()
    fun projectDirectory(id: String): File = File(root, requireSafeId(id))
    fun exists(id: String): Boolean = File(projectDirectory(id), MANIFEST).isFile

    fun list(): List<DrawingSummary> = root.listFiles().orEmpty().mapNotNull { directory ->
        if (!directory.isDirectory || directory.name.startsWith(".")) return@mapNotNull null
        runCatching {
            val json = JSONObject(File(directory, MANIFEST).readText())
            DrawingSummary(
                id = json.getString("id"), name = json.getString("name"),
                widthPx = json.getInt("widthPx"), heightPx = json.getInt("heightPx"),
                modifiedAtMillis = json.getLong("modifiedAtMillis"),
                thumbnailFile = File(directory, THUMBNAIL),
            )
        }.getOrNull()
    }.sortedByDescending { it.modifiedAtMillis }

    fun delete(id: String): Boolean {
        val directory = projectDirectory(id)
        if (!directory.exists() || directory.parentFile != root) return false
        directory.deleteRecursively()
        return !directory.exists()
    }

    companion object {
        internal const val MANIFEST = "manifest.json"
        internal const val THUMBNAIL = "thumbnail.png"
        private fun requireSafeId(id: String): String {
            require(id.matches(Regex("[A-Za-z0-9_-]+"))) { "Invalid drawing ID" }
            return id
        }
    }
}

enum class ExportFormat(val extension: String, val mimeType: String) {
    PNG("png", "image/png"), JPEG("jpg", "image/jpeg"), WEBP("webp", "image/webp")
}

internal sealed interface SavedLayerSnapshot {
    val id: LayerId
    val name: String
    val visible: Boolean
    val opacity: Float
}

internal data class SavedRasterSnapshot(
    override val id: LayerId,
    override val name: String,
    override val visible: Boolean,
    override val opacity: Float,
    val tiles: Map<TileCoordinate, Bitmap>,
    val colorUsage: Map<Int, Long> = emptyMap(),
) : SavedLayerSnapshot

internal data class SavedImageSnapshot(
    override val id: LayerId,
    override val name: String,
    override val visible: Boolean,
    override val opacity: Float,
    val sourceUri: Uri,
    val originalWidthPx: Int,
    val originalHeightPx: Int,
    val transform: ImageTransform,
) : SavedLayerSnapshot

internal data class DrawingSnapshot(
    val widthPx: Int,
    val heightPx: Int,
    val selectedId: LayerId,
    val layers: List<SavedLayerSnapshot>,
) {
    fun recycle() = layers.filterIsInstance<SavedRasterSnapshot>().flatMap { it.tiles.values }.forEach { if (!it.isRecycled) it.recycle() }
}

internal data class LoadedProject(
    val widthPx: Int,
    val heightPx: Int,
    val selectedId: LayerId,
    val layers: List<LoadedLayer>,
)

internal sealed interface LoadedLayer {
    val id: LayerId
    val name: String
    val visible: Boolean
    val opacity: Float
}

internal data class LoadedRaster(
    override val id: LayerId, override val name: String, override val visible: Boolean, override val opacity: Float,
    val tiles: Map<TileCoordinate, Bitmap>, val colorUsage: Map<Int, Long> = emptyMap(),
) : LoadedLayer

internal data class LoadedImage(
    override val id: LayerId, override val name: String, override val visible: Boolean, override val opacity: Float,
    val assetFile: File, val transform: ImageTransform,
) : LoadedLayer

internal object ProjectPersistence {
    private const val SCHEMA_VERSION = 1
    private const val TILE_SIZE = 256
    val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "TipStroke-project-io").apply { isDaemon = true } }
    val mainHandler = Handler(Looper.getMainLooper())

    fun save(resolver: ContentResolver, library: DrawingLibrary, id: String, name: String, snapshot: DrawingSnapshot): Result<DrawingSummary> = runCatching {
        val target = library.projectDirectory(id)
        val temporary = File(library.root, ".$id-${UUID.randomUUID()}.saving")
        temporary.mkdirs()
        try {
            val layersJson = JSONArray()
            snapshot.layers.forEach { layer ->
                val json = JSONObject()
                    .put("id", layer.id.value).put("name", layer.name)
                    .put("visible", layer.visible).put("opacity", layer.opacity)
                when (layer) {
                    is SavedRasterSnapshot -> {
                        json.put("kind", "raster")
                        json.put("colorUsage", JSONArray().apply {
                            layer.colorUsage.entries.sortedByDescending { it.value }.forEach { (argb, weight) ->
                                put(JSONObject().put("argb", argb).put("weight", weight))
                            }
                        })
                        val tileDirectory = File(temporary, "layers/${layer.id.value}/tiles").apply { mkdirs() }
                        layer.tiles.forEach { (coordinate, bitmap) ->
                            File(tileDirectory, "${coordinate.x}_${coordinate.y}.png").outputStream().use {
                                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                            }
                        }
                    }
                    is SavedImageSnapshot -> {
                        json.put("kind", "image")
                            .put("originalWidthPx", layer.originalWidthPx).put("originalHeightPx", layer.originalHeightPx)
                            .put("centerX", layer.transform.centerX).put("centerY", layer.transform.centerY)
                            .put("scale", layer.transform.scale).put("rotationDegrees", layer.transform.rotationDegrees)
                        val asset = File(temporary, "assets/${layer.id.value}.source")
                        asset.parentFile?.mkdirs()
                        val sourceStream = if (layer.sourceUri.scheme == ContentResolver.SCHEME_FILE) {
                            File(requireNotNull(layer.sourceUri.path)).inputStream()
                        } else resolver.openInputStream(layer.sourceUri)
                        sourceStream.use { input ->
                            requireNotNull(input) { "Cannot reopen ${layer.name}" }
                            asset.outputStream().use(input::copyTo)
                        }
                    }
                }
                layersJson.put(json)
            }
            val modifiedAt = System.currentTimeMillis()
            JSONObject()
                .put("schemaVersion", SCHEMA_VERSION).put("id", id).put("name", name)
                .put("widthPx", snapshot.widthPx).put("heightPx", snapshot.heightPx)
                .put("modifiedAtMillis", modifiedAt)
                .put("selectedLayerId", snapshot.selectedId.value)
                .put("layers", layersJson)
                .also { File(temporary, DrawingLibrary.MANIFEST).writeText(it.toString(2)) }

            val thumbnailScale = minOf(1f, 640f / snapshot.widthPx, 420f / snapshot.heightPx)
            render(snapshot, resolver, thumbnailScale, transparent = false).also { thumbnail ->
                File(temporary, DrawingLibrary.THUMBNAIL).outputStream().use { thumbnail.compress(Bitmap.CompressFormat.PNG, 100, it) }
                thumbnail.recycle()
            }
            replaceDirectoryAtomically(temporary, target)
            DrawingSummary(id, name, snapshot.widthPx, snapshot.heightPx, modifiedAt, File(target, DrawingLibrary.THUMBNAIL))
        } catch (failure: Throwable) {
            temporary.deleteRecursively()
            throw failure
        }
    }

    fun load(directory: File): Result<LoadedProject> = runCatching {
        val json = JSONObject(File(directory, DrawingLibrary.MANIFEST).readText())
        require(json.getInt("schemaVersion") == SCHEMA_VERSION) { "Unsupported drawing version" }
        val width = json.getInt("widthPx")
        val height = json.getInt("heightPx")
        val layersJson = json.getJSONArray("layers")
        val layers = buildList {
            for (index in 0 until layersJson.length()) {
                val layer = layersJson.getJSONObject(index)
                val id = LayerId(layer.getString("id"))
                val name = layer.getString("name")
                val visible = layer.optBoolean("visible", true)
                val opacity = layer.optDouble("opacity", 1.0).toFloat()
                if (layer.getString("kind") == "raster") {
                    val tileDirectory = File(directory, "layers/${id.value}/tiles")
                    val tiles = tileDirectory.listFiles().orEmpty().mapNotNull { file ->
                        val match = Regex("(-?\\d+)_(-?\\d+)\\.png").matchEntire(file.name) ?: return@mapNotNull null
                        val decoded = BitmapFactory.decodeFile(file.absolutePath) ?: return@mapNotNull null
                        val bitmap = decoded.copy(Bitmap.Config.ARGB_8888, true).also { decoded.recycle() }
                        TileCoordinate(match.groupValues[1].toInt(), match.groupValues[2].toInt()) to bitmap
                    }.toMap()
                    val usage = if (layer.has("colorUsage")) {
                        buildMap {
                            val entries = layer.optJSONArray("colorUsage") ?: JSONArray()
                            for (usageIndex in 0 until entries.length()) {
                                val entry = entries.getJSONObject(usageIndex)
                                val weight = entry.optLong("weight", 0L)
                                if (weight > 0L) put(entry.getInt("argb"), weight)
                            }
                        }
                    } else inferColorUsage(tiles.values)
                    add(LoadedRaster(id, name, visible, opacity, tiles, usage))
                } else {
                    add(LoadedImage(
                        id, name, visible, opacity, File(directory, "assets/${id.value}.source"),
                        ImageTransform(
                            layer.getDouble("centerX").toFloat(), layer.getDouble("centerY").toFloat(),
                            layer.getDouble("scale").toFloat(), layer.optDouble("rotationDegrees", 0.0).toFloat(),
                        ),
                    ))
                }
            }
        }
        require(layers.isNotEmpty()) { "Drawing contains no layers" }
        val selected = LayerId(json.optString("selectedLayerId", layers.last().id.value)).let { wanted ->
            if (layers.any { it.id == wanted }) wanted else layers.last().id
        }
        LoadedProject(width, height, selected, layers)
    }

    fun export(resolver: ContentResolver, uri: Uri, snapshot: DrawingSnapshot, format: ExportFormat, quality: Int, scale: Float, transparent: Boolean): Result<Unit> = runCatching {
        require(scale > 0f)
        val pixels = snapshot.widthPx.toLong() * snapshot.heightPx.toLong() * scale * scale
        require(pixels <= 67_108_864L) { "Export is too large for this device" }
        val bitmap = render(snapshot, resolver, scale, transparent && format != ExportFormat.JPEG)
        try {
            resolver.openOutputStream(uri, "w").use { output ->
                requireNotNull(output) { "Cannot open export destination" }
                val compressFormat = when (format) {
                    ExportFormat.PNG -> Bitmap.CompressFormat.PNG
                    ExportFormat.JPEG -> Bitmap.CompressFormat.JPEG
                    ExportFormat.WEBP -> if (android.os.Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
                }
                check(bitmap.compress(compressFormat, quality.coerceIn(1, 100), output)) { "Image encoder failed" }
            }
        } finally { bitmap.recycle() }
    }

    private fun render(snapshot: DrawingSnapshot, resolver: ContentResolver, scale: Float, transparent: Boolean): Bitmap {
        val outputWidth = (snapshot.widthPx * scale).roundToInt().coerceAtLeast(1)
        val outputHeight = (snapshot.heightPx * scale).roundToInt().coerceAtLeast(1)
        val output = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        if (!transparent) canvas.drawColor(Color.WHITE)
        canvas.scale(scale, scale)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        snapshot.layers.forEach { layer ->
            if (!layer.visible || layer.opacity <= 0f) return@forEach
            paint.alpha = (layer.opacity * 255).roundToInt().coerceIn(0, 255)
            when (layer) {
                is SavedRasterSnapshot -> layer.tiles.forEach { (coordinate, bitmap) ->
                    canvas.drawBitmap(bitmap, (coordinate.x * TILE_SIZE).toFloat(), (coordinate.y * TILE_SIZE).toFloat(), paint)
                }
                is SavedImageSnapshot -> {
                    val source = decodeBitmap(resolver, layer.sourceUri)
                    try {
                        val width = layer.originalWidthPx * layer.transform.scale
                        val height = layer.originalHeightPx * layer.transform.scale
                        canvas.save()
                        canvas.rotate(layer.transform.rotationDegrees, layer.transform.centerX, layer.transform.centerY)
                        canvas.drawBitmap(source, null, RectF(layer.transform.centerX - width / 2, layer.transform.centerY - height / 2, layer.transform.centerX + width / 2, layer.transform.centerY + height / 2), paint)
                        canvas.restore()
                    } finally { source.recycle() }
                }
            }
        }
        return output
    }

    private fun decodeBitmap(resolver: ContentResolver, uri: Uri): Bitmap =
        ImageDecoder.decodeBitmap(if (uri.scheme == ContentResolver.SCHEME_FILE) ImageDecoder.createSource(File(requireNotNull(uri.path))) else ImageDecoder.createSource(resolver, uri)) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }

    /** Used only while loading pre-palette projects on the project I/O thread. */
    private fun inferColorUsage(tiles: Collection<Bitmap>): Map<Int, Long> {
        val quantized = mutableMapOf<Int, Long>()
        tiles.forEach { bitmap ->
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            for (y in 0 until bitmap.height step 4) {
                for (x in 0 until bitmap.width step 4) {
                    val pixel = pixels[y * bitmap.width + x]
                    val alpha = Color.alpha(pixel)
                    if (alpha < 16) continue
                    val key = Color.rgb(
                        Color.red(pixel) and 0xF8,
                        Color.green(pixel) and 0xF8,
                        Color.blue(pixel) and 0xF8,
                    )
                    quantized[key] = quantized.getOrDefault(key, 0L) + alpha
                }
            }
        }
        return quantized.entries.sortedByDescending { it.value }.take(16).associate { it.toPair() }
    }

    private fun replaceDirectoryAtomically(temporary: File, target: File) {
        val backup = File(target.parentFile, ".${target.name}.backup")
        backup.deleteRecursively()
        if (target.exists()) check(target.renameTo(backup)) { "Cannot prepare existing drawing for replacement" }
        try {
            try {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath())
            }
            backup.deleteRecursively()
        } catch (failure: Throwable) {
            if (!target.exists() && backup.exists()) backup.renameTo(target)
            throw failure
        }
    }
}
